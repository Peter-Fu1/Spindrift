"""Check APK's actual decoded launcher PNGs against all 15 generated resources."""
from pathlib import Path
from PIL import Image
import hashlib,io,json,zipfile,argparse
ROOT=Path(__file__).resolve().parents[1]
def pixels(im):
 im=im.convert('RGBA');data=bytearray(im.tobytes())
 for i in range(3,len(data),4):
  if data[i]==0:data[i-3:i]=b'\0\0\0'
 return str(im.size)+':'+hashlib.sha256(data).hexdigest()
def verify(apk,root=ROOT):
 source={}
 for p in (Path(root)/'app/src/main/res').glob('mipmap-*/*.png'):
  if p.name in ['ic_launcher.png','ic_launcher_round.png','ic_launcher_foreground.png']:source[str(p.relative_to(root))]=(pixels(Image.open(p)),hashlib.sha256(p.read_bytes()).hexdigest())
 packed={}
 with zipfile.ZipFile(apk) as z:
  for name in z.namelist():
   if name.startswith('res/') and name.endswith('.png'):
    raw=z.read(name)
    try:packed.setdefault(pixels(Image.open(io.BytesIO(raw))),[]).append(dict(path=name,sha256=hashlib.sha256(raw).hexdigest()))
    except OSError:pass
 rows=[dict(source=k,sourceSha256=sha,matchingApkEntries=packed.get(value,[]),passed=value in packed) for k,(value,sha) in source.items()]
 return dict(apk=str(Path(apk).resolve()),files=rows,passed=len(rows)==15 and all(x['passed'] for x in rows),comparison='decoded RGBA; invisible RGB normalized; aapt PNG encoding may change')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('--report',required=True);args=p.parse_args();r=verify(args.apk);Path(args.report).write_text(json.dumps(r,indent=2),encoding='utf8');print(json.dumps(dict(passed=r['passed'],files=len(r['files']))));raise SystemExit(0 if r['passed'] else 1)
