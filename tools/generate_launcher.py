"""Generate launcher resources without modifying the source PNG.
The unzoomed 72dp photo fills the visible adaptive viewport. Only pixels OUTSIDE
that viewport are edge-extended to the 108dp parallax layer, avoiding a flat ring.
"""
from pathlib import Path
from PIL import Image,ImageDraw
import hashlib,json,argparse
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'tools/launcher/Eula_Icon_2.png'
SOURCE_SHA256='27932baaa03c521ec58f2fd195ada5f115ccd8e1e2dfd407a4db6fab18cee5e5'
DENSITIES=[('mdpi',1),('hdpi',1.5),('xhdpi',2),('xxhdpi',3),('xxxhdpi',4)]
def layer(source,size,portrait_size):
 p=source.resize((portrait_size,portrait_size),Image.Resampling.LANCZOS);pad=(size-portrait_size)//2;out=Image.new('RGBA',(size,size));out.paste(p,(pad,pad));end=pad+portrait_size
 out.paste(p.crop((0,0,portrait_size,1)).resize((portrait_size,pad)),(pad,0));out.paste(p.crop((0,portrait_size-1,portrait_size,portrait_size)).resize((portrait_size,size-end)),(pad,end))
 out.paste(p.crop((0,0,1,portrait_size)).resize((pad,portrait_size)),(0,pad));out.paste(p.crop((portrait_size-1,0,portrait_size,portrait_size)).resize((size-end,portrait_size)),(end,pad))
 for x,y,c in [(0,0,p.getpixel((0,0))),(end,0,p.getpixel((portrait_size-1,0))),(0,end,p.getpixel((0,portrait_size-1))),(end,end,p.getpixel((portrait_size-1,portrait_size-1)))]:ImageDraw.Draw(out).rectangle((x,y,x+(pad if x==0 else size-end)-1,y+(pad if y==0 else size-end)-1),fill=c)
 return out
def generate(source_path=SOURCE,res=None,evidence=None):
 res=Path(res or ROOT/'app/src/main/res');data=Path(source_path).read_bytes()
 if hashlib.sha256(data).hexdigest()!=SOURCE_SHA256:raise ValueError('Wrong authoritative launcher input')
 original=Image.open(source_path).convert('RGBA');bbox=original.getchannel('A').getbbox();source=original.crop(bbox)
 if source.width!=source.height:raise ValueError('Square source required; do not stretch artwork')
 files=[]
 for density,scale in DENSITIES:
  folder=res/('mipmap-'+density);folder.mkdir(parents=True,exist_ok=True);size=round(48*scale);portrait=source.resize((size,size),Image.Resampling.LANCZOS)
  portrait.save(folder/'ic_launcher.png');rounded=portrait.copy();mask=Image.new('L',(size,size));ImageDraw.Draw(mask).ellipse((0,0,size-1,size-1),fill=255);rounded.putalpha(mask);rounded.save(folder/'ic_launcher_round.png')
  adaptive=layer(source,round(108*scale),round(72*scale));adaptive.save(folder/'ic_launcher_foreground.png')
  for name in ['ic_launcher.png','ic_launcher_round.png','ic_launcher_foreground.png']:
   f=folder/name;im=Image.open(f);files.append(dict(file=str(f.relative_to(res)),size=im.size,alphaBbox=im.getchannel('A').getbbox(),sha256=hashlib.sha256(f.read_bytes()).hexdigest()))
 xml='<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n <background android:drawable="@color/launcher_background"/>\n <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n</adaptive-icon>\n'
 folder=res/'mipmap-anydpi-v26';folder.mkdir(exist_ok=True)
 for name in ['ic_launcher.xml','ic_launcher_round.xml']:(folder/name).write_text(xml,encoding='utf8')
 (res/'values').mkdir(exist_ok=True);(res/'values/launcher_colors.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="launcher_background">#dce8f0</color></resources>\n',encoding='utf8')
 result=dict(sourceSha256=SOURCE_SHA256,sourceSize=original.size,sourceAlphaBbox=bbox,portraitDp=72,oldPortraitDp=64,adaptiveLayerDp=108,noExtraFlatRing=True,monochrome='not present; no color bitmap assigned as monochrome',files=files)
 if evidence:
  out=Path(evidence);out.mkdir(parents=True,exist_ok=True);(out/'icon-generation.json').write_text(json.dumps(result,indent=2),encoding='utf8')
  sheet=Image.new('RGB',(800,480),'white');draw=ImageDraw.Draw(sheet)
  for col,label in enumerate(['64dp circle','72dp circle','64dp rounded','72dp rounded']):
   for row,bg in enumerate(['#ffffff','#202735']):
    x=col*200;y=row*240;draw.rectangle((x,y,x+199,y+239),fill=bg)
    if col%2==0:
     canvas=Image.new('RGBA',(270,270),'#dce8f0');small=source.resize((160,160),Image.Resampling.LANCZOS);canvas.paste(small,(55,55));icon=canvas.crop((45,45,225,225))
    else:icon=layer(source,270,180).crop((45,45,225,225))
    mask=Image.new('L',(180,180));d=ImageDraw.Draw(mask)
    if col<2:d.ellipse((0,0,179,179),fill=255)
    else:d.rounded_rectangle((0,0,179,179),radius=40,fill=255)
    icon.putalpha(mask);sheet.paste(icon,(x+10,y+10),icon);draw.text((x+12,y+205),label,fill='#718096')
  sheet.save(out/'launcher-comparison.png')
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--evidence',default=str(ROOT/'work/stage41/icons'));args=p.parse_args();print(json.dumps(generate(evidence=args.evidence),indent=2))
