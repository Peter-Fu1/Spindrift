"""Sign locally with the persistent private PKCS12; cloud never receives the private key.
Usage: python tools/sign_preview.py <unsigned-apk> <apksigner.jar> <output-apk>
The default key/password live OUTSIDE this checkout. Windows user DPAPI protects the password.
"""
from pathlib import Path
import subprocess,sys,os,hashlib,json
def main():
 unsigned,jar,output=map(Path,sys.argv[1:4])
 root=Path(os.environ['USERPROFILE'])/'.hoyoreminder-signing'
 key=root/'release-v1.p12';protected=root/'release-v1.password.dpapi'
 if not key.is_file() or not protected.is_file():raise SystemExit('Existing persistent key unavailable; refusing a replacement identity.')
 # Independent DPAPI unprotect; do not depend on another repository at runtime.
 import ctypes
 from ctypes import wintypes
 class Blob(ctypes.Structure):_fields_=[('size',wintypes.DWORD),('data',ctypes.POINTER(ctypes.c_ubyte))]
 raw=protected.read_bytes();buffer=ctypes.create_string_buffer(raw);source=Blob(len(raw),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)));target=Blob()
 if not ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(source),None,None,None,None,1,ctypes.byref(target)):raise ctypes.WinError()
 try:password=ctypes.string_at(target.data,target.size).decode()
 finally:ctypes.windll.kernel32.LocalFree(target.data)
 env={**os.environ,'HOYO_LOCAL_SIGN_PASSWORD':password}
 cert=subprocess.check_output([str(Path(os.environ['JAVA_HOME'])/'bin/keytool.exe') if os.environ.get('JAVA_HOME') else 'keytool','-exportcert','-alias','hoyoreminder','-keystore',str(key),'-storepass:env','HOYO_LOCAL_SIGN_PASSWORD'],env=env,stderr=subprocess.DEVNULL)
 certificate=hashlib.sha256(cert).hexdigest()
 expected=(Path(__file__).resolve().parent/'signing-certificate.sha256').read_text().strip()
 if certificate!=expected:raise SystemExit('Signing certificate does not match the committed allowlist')
 output.parent.mkdir(parents=True,exist_ok=True)
 command=['java','-jar',str(jar)]
 subprocess.run(command+['sign','--ks',str(key),'--ks-key-alias','hoyoreminder','--ks-pass','env:HOYO_LOCAL_SIGN_PASSWORD','--key-pass','env:HOYO_LOCAL_SIGN_PASSWORD','--out',str(output),str(unsigned)],env=env,check=True)
 verification=subprocess.check_output(command+['verify','--verbose','--print-certs',str(output)],text=True,encoding='utf-8')
 if certificate not in verification.lower().replace(':',''):raise SystemExit('Signed APK does not contain the expected certificate')
 (output.parent/(output.name+'.signature.txt')).write_text(verification,encoding='utf-8')
 print(json.dumps({'apk':str(output.resolve()),'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'certificateSha256':certificate,'bytes':output.stat().st_size,'privateMaterialUploaded':False}))
if __name__=='__main__':main()
