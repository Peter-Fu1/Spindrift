"""Packaging failures must block delivery even when Gradle compilation succeeded."""
from pathlib import Path
import contextlib
import io
import json
import tempfile
import unittest
import zipfile
from verify_apk_ui import UI_FILES, verify_apk, main

class VerifyApkUiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in UI_FILES:
            file = self.root / 'web' / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text('synthetic UI text: ' + name, encoding='utf-8')

    def apk(self, changes=None, missing=None, extra=None):
        file = self.root / 'fixture.apk'
        with zipfile.ZipFile(file, 'w') as archive:
            for name in UI_FILES:
                if name == missing:
                    continue
                archive.writestr('assets/web/' + name, (changes or {}).get(name, (self.root / 'web' / name).read_bytes()))
            if extra:
                archive.writestr('assets/web/' + extra, b'development fixture')
        return file

    def test_current_sources_pass_without_android_sdk(self):
        result = verify_apk(self.apk(), self.root)
        self.assertTrue(result['passed'])
        self.assertEqual(len(result['files']), 11)

    def test_stale_index_or_script_fails_even_if_apk_exists(self):
        for name in ['index.html', 'app.js']:
            with self.subTest(file=name):
                result = verify_apk(self.apk({name: b'old UI wording'}), self.root)
                self.assertFalse(result['passed'])
                self.assertIn({'kind': 'asset_mismatch', 'file': name}, result['errors'])

    def test_missing_ui_file_fails(self):
        result = verify_apk(self.apk(missing='checkin-ui.js'), self.root)
        self.assertFalse(result['passed'])
        self.assertIn({'kind': 'asset_missing', 'file': 'checkin-ui.js'}, result['errors'])

    def test_preview_or_other_unexpected_web_asset_fails(self):
        result = verify_apk(self.apk(extra='tools/ui-preview/adapter.js'), self.root)
        self.assertFalse(result['passed'])

    def test_docs_backup_cannot_replace_formal_root_index(self):
        (self.root / 'docs').mkdir()
        (self.root / 'docs/index.html').write_text('old docs backup', encoding='utf-8')
        file = self.apk({'index.html': (self.root / 'docs/index.html').read_bytes()})
        self.assertFalse(verify_apk(file, self.root)['passed'])

    def test_unreadable_apk_fails(self):
        file = self.root / 'broken.apk'
        file.write_bytes(b'not an APK')
        self.assertFalse(verify_apk(file, self.root)['passed'])

    def test_cli_report_is_readable_json_and_reports_actual_ui_hashes(self):
        report = self.root / 'report.json'
        with contextlib.redirect_stdout(io.StringIO()):
            code = main(['--root', str(self.root), '--report', str(report), str(self.apk())])
        self.assertEqual(code, 0)
        value = json.loads(report.read_text(encoding='utf-8'))
        self.assertTrue(value['passed'])
        self.assertEqual(len(value['apks'][0]['files']), 11)

    def test_windows_crlf_checkout_matches_cloud_lf_without_hiding_text_changes(self):
        (self.root / 'web/index.html').write_bytes(b'UI line one\r\nUI line two\r\n')
        result = verify_apk(self.apk({'index.html': b'UI line one\nUI line two\n'}), self.root)
        self.assertTrue(result['passed'])
        self.assertFalse(next(x for x in result['files'] if x['file']=='index.html')['rawMatches'])
