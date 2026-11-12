"""Main-only local key operations. Never create a key as a side effect of signing.
All child-process secrets use transient environment variables; errors never echo commands/output.
"""
from pathlib import Path
import os,sys,json,subprocess,hashlib,secrets,ctypes
from ctypes import wintypes
ROOT=Path(__file__).resolve().parents[1]
LOCK=ROOT/'tools/main-release-signing-lock.json'
LEDGER=ROOT/'tools/main-release-ledger.json'
APP_ID='io.github.peterfu.hoyoreminder'
PREVIEW_CERT='5e13e4533deb59d0ccb28632e6680fdd56e9f111961f6df0878d2bce82436c4b'
ALIAS='main-release-v1'
class ReleaseError(Exception):pass

def load(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def save(path,value):
 p=Path(path);p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def home():
 p=Path(os.environ['USERPROFILE'])/'.hoyoreminder-signing'
 if p.resolve().is_relative_to(ROOT.resolve()):raise ReleaseError('Private signing storage must be outside checkout')
 return p

def key_paths():
 p=home();return p/'main-release-v1.p12',p/'main-release-v1.password.dpapi'
def keytool():
 p=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-17'))/'bin'/('keytool.exe' if os.name=='nt' else 'keytool')
 return str(p) if p.is_file() else 'keytool'
def run(args,env=None,binary=False):
 p=subprocess.run([str(a) for a in args],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if p.returncode:raise ReleaseError('Required release tool failed; private output suppressed')
 return p.stdout if binary else p.stdout.decode('utf-8',errors='replace')
def dpapi(raw,decrypt=False):
 if os.name!='nt':raise ReleaseError('Local main key requires Windows user DPAPI')
 class Blob(ctypes.Structure):_fields_=[('size',wintypes.DWORD),('data',ctypes.POINTER(ctypes.c_ubyte))]
 buf=ctypes.create_string_buffer(raw);source=Blob(len(raw),ctypes.cast(buf,ctypes.POINTER(ctypes.c_ubyte)));target=Blob()
 if decrypt:ok=ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(source),None,None,None,None,1,ctypes.byref(target))
 else:ok=ctypes.windll.crypt32.CryptProtectData(ctypes.byref(source),None,None,None,None,1,ctypes.byref(target))
 if not ok:raise ReleaseError('Windows secure storage unavailable')
 try:return ctypes.string_at(target.data,target.size)
 finally:ctypes.windll.kernel32.LocalFree(target.data)
def local_password():
 key,protected=key_paths()
 if not key.is_file() or not protected.is_file():raise ReleaseError('Existing MAIN key unavailable; never generate a replacement')
 return dpapi(protected.read_bytes(),True).decode('ascii')
def key_info(key,password):
 env={**os.environ,'HOYO_MAIN_KEY_PASSWORD':password}
 info=run([keytool(),'-J-Duser.language=en','-J-Duser.country=US','-list','-v','-keystore',key,'-alias',ALIAS,'-storepass:env','HOYO_MAIN_KEY_PASSWORD'],env)
 if 'PrivateKeyEntry' not in info:raise ReleaseError('MAIN alias is not a PrivateKeyEntry')
 cert=run([keytool(),'-exportcert','-alias',ALIAS,'-keystore',key,'-storepass:env','HOYO_MAIN_KEY_PASSWORD'],env,True)
 return {'privateKeyEntry':True,'certificateSha256':hashlib.sha256(cert).hexdigest(),'alias':ALIAS}
def validate_lock(lock):
 if lock.get('applicationId')!=APP_ID or lock.get('allowedVariant')!='release' or lock.get('alias')!=ALIAS:raise ReleaseError('Wrong MAIN signing channel')
 cert=lock.get('certificateSha256')
 if not isinstance(cert,str) or len(cert)!=64 or any(c not in '0123456789abcdef' for c in cert) or cert==PREVIEW_CERT:raise ReleaseError('Uninitialized or Preview signing identity')
 if lock.get('signatureSchemes')!=['v2','v3']:raise ReleaseError('MAIN signature scheme lock changed')
 return cert

def init():
 key,protected=key_paths();lock=load(LOCK)
 if key.exists() or protected.exists() or lock.get('certificateSha256') or load(LEDGER).get('releases'):raise ReleaseError('MAIN identity or ledger already exists; refusing overwrite/recreation')
 home().mkdir(exist_ok=True)
 user=run(['whoami']).strip()
 run(['icacls',home(),'/inheritance:r','/grant:r',user+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'])
 password=secrets.token_urlsafe(48)
 # Persist recoverable OS-protected password BEFORE key generation; partial state fails closed.
 with protected.open('xb') as f:f.write(dpapi(password.encode('ascii')))
 env={**os.environ,'HOYO_MAIN_KEY_PASSWORD':password}
 run([keytool(),'-genkeypair','-alias',ALIAS,'-keyalg','RSA','-keysize','3072','-validity','10000','-storetype','PKCS12','-keystore',key,'-storepass:env','HOYO_MAIN_KEY_PASSWORD','-keypass:env','HOYO_MAIN_KEY_PASSWORD','-dname','CN=HoYoReminder Main Release v1, O=HoYoReminder, C=CN'],env)
 info=key_info(key,password)
 if info['certificateSha256']==PREVIEW_CERT:raise ReleaseError('MAIN cannot reuse Preview identity')
 lock.update(certificateSha256=info['certificateSha256'],state='locked')
 save(LOCK,lock)
 print(json.dumps({**info,'keyId':'main-release-v1','passwordStorage':'Windows current-user DPAPI','privateMaterialUploaded':False,'backup_restoration_verified':False}))
if __name__=='__main__':
 try:
  if sys.argv[1:]!=['init']:raise ReleaseError('Only explicit init is supported here; use release_main.py to sign')
  init()
 except ReleaseError as e:print(str(e),file=sys.stderr);sys.exit(1)
