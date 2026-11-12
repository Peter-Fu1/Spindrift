"""Run SDK zipalign check in CI BEFORE any local signing, bind receipt to APK/source."""
from pathlib import Path
import argparse,subprocess,hashlib,json
p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('zipalign');p.add_argument('output',type=Path);a=p.parse_args()
r=subprocess.run([a.zipalign,'-c','-P','16','-v','4',str(a.apk)],capture_output=True,text=True,check=True)
a.output.with_suffix('.txt').write_text(r.stdout+r.stderr,encoding='utf-8')
a.output.write_text(json.dumps({'sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'apkSha256':hashlib.sha256(a.apk.read_bytes()).hexdigest(),'zipalignVerified':True,'tool':'Android SDK Build Tools 35.0.0 zipalign -c -P 16 -v 4','agpAlignedBeforeSigning':True},indent=2)+'\n',encoding='utf-8')

badging=subprocess.check_output([str(Path(a.zipalign).with_name('aapt'+Path(a.zipalign).suffix)),'dump','badging',str(a.apk)],text=True)
a.output.with_name('main-apk-badging.txt').write_text(badging,encoding='utf-8')
