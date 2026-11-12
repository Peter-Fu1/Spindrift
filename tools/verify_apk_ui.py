"""Verify packaged UI against the checked-out Android sources; no SDK or third-party modules.
Usage: python tools/verify_apk_ui.py --report apk-ui-verification.json <apk> [<apk> ...]
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import zipfile

UI_FILES = (
    'index.html', 'app.js', 'platform-adapter.js', 'styles.css',
    'resource-estimate.js', 'ui-model.js', 'checkin-ui.js', 'sms-ui.js',
    'normalization.mjs', 'refresh-contract.mjs', 'shared/platform-contract.mjs',
)

def digest(value):
    return hashlib.sha256(value).hexdigest()

def canonical_text(value):
    # Windows checkout may use CRLF, while Actions checks out LF. No other text is altered.
    return value.replace(b'\r\n', b'\n')

def verify_apk(apk, source_root):
    apk, source_root = Path(apk), Path(source_root)
    result = {'apk': str(apk), 'passed': False, 'files': [], 'errors': []}
    try:
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if n.startswith('assets/web/') and not n.endswith('/')]
            expected = {'assets/web/' + name for name in UI_FILES}
            for name in sorted(set(names) - expected):
                result['errors'].append({'kind': 'unexpected_asset', 'file': name})
            if len(names) != len(set(names)):
                result['errors'].append({'kind': 'duplicate_asset'})
            for name in UI_FILES:
                target = 'assets/web/' + name
                source = source_root / 'web' / name
                if not source.is_file():
                    result['errors'].append({'kind': 'source_missing', 'file': name})
                    continue
                if target not in names:
                    result['errors'].append({'kind': 'asset_missing', 'file': name})
                    continue
                original, packaged = source.read_bytes(), archive.read(target)
                matches = canonical_text(original) == canonical_text(packaged)
                result['files'].append({'file': name, 'matches': matches,
                                        'rawMatches': original == packaged, 'sourceSha256': digest(original), 'apkSha256': digest(packaged),
                                        'normalizedSourceSha256': digest(canonical_text(original)),
                                        'normalizedApkSha256': digest(canonical_text(packaged))})
                if not matches:
                    result['errors'].append({'kind': 'asset_mismatch', 'file': name})
    except (OSError, zipfile.BadZipFile, KeyError):
        result['errors'].append({'kind': 'apk_unreadable'})
    result['passed'] = not result['errors'] and len(result['files']) == len(UI_FILES)
    return result

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--report', type=Path)
    parser.add_argument('apks', nargs='+', type=Path)
    args = parser.parse_args(argv)
    results = [verify_apk(apk, args.root) for apk in args.apks]
    revision = subprocess.run(['git', '-C', str(args.root), 'rev-parse', 'HEAD'],
                              capture_output=True, text=True, check=False)
    report = {'schemaVersion': 1, 'sourceRevision': revision.stdout.strip() or 'unknown',
              'passed': all(row['passed'] for row in results), 'apks': results}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
