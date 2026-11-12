import tempfile,unittest,hashlib
from pathlib import Path
from PIL import Image
from generate_launcher import generate,SOURCE,SOURCE_SHA256,DENSITIES,layer,ROOT
class LauncherTest(unittest.TestCase):
 def test_authoritative_new_source_is_unmodified_full_bleed(self):
  self.assertEqual(hashlib.sha256(SOURCE.read_bytes()).hexdigest(),SOURCE_SHA256);im=Image.open(SOURCE);self.assertEqual(im.size,(1280,1280));self.assertEqual(im.getchannel('A').getextrema(),(255,255));self.assertEqual(im.getchannel('A').getbbox(),(0,0,1280,1280))
 def test_generated_resources_are_reproducible_all_densities(self):
  with tempfile.TemporaryDirectory() as temp:
   res=Path(temp)/'res';value=generate(res=res);self.assertEqual(len(value['files']),15)
   for row in value['files']:
    generated=Image.open(res/row['file']).convert('RGBA');committed=Image.open(ROOT/'app/src/main/res'/row['file']).convert('RGBA');self.assertEqual(generated.size,committed.size);self.assertEqual(generated.tobytes(),committed.tobytes()) # PNG deflate encoding differs across OS/zlib, pixels must be exact
 def test_unzoomed_portrait_has_no_extra_transparent_ring(self):
  original=Image.open(SOURCE).convert('RGBA')
  for name,scale in DENSITIES:
   size=round(108*scale);portrait=round(72*scale);pad=(size-portrait)//2;result=layer(original,size,portrait)
   self.assertEqual(result.getchannel('A').getextrema(),(255,255));self.assertEqual(result.crop((pad,pad,pad+portrait,pad+portrait)).tobytes(),original.resize((portrait,portrait),Image.Resampling.LANCZOS).tobytes())
