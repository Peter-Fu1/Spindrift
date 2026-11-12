"""Owner-run encrypted MAIN backup and private-key restoration drill.
Passwords are entered with getpass in a real local terminal, never via chat or command args.
"""
from pathlib import Path
import argparse,sys,os,getpass,shutil,tempfile,re
from main_signing import *

def destination_ok(destination):
 p=Path(destination).resolve()
 if p.suffix.lower()!='.p12' or p.exists() or not p.parent.is_dir() or p.is_relative_to(ROOT.resolve()) or p.is_relative_to(home().resolve()):raise ReleaseError('Use a NEW .p12 in an existing independent private directory outside all checkouts/signing storage')
 for parent in p.parents:
  if (parent/'.git').exists():raise ReleaseError('Signing backups cannot be placed in any Git checkout')
 return p

def backup(destination,independence_id,off_host,jar,unsigned):
 cert=validate_lock(load(LOCK));destination=destination_ok(destination)
 if not sys.stdin.isatty():raise ReleaseError('Owner must enter backup password in a local interactive terminal')
 if not re.fullmatch('[A-Za-z0-9_-]{3,48}',independence_id):raise ReleaseError('Use a non-secret medium identifier')
 receipt_path=home()/'main-backup-receipt.json'
 receipt=load(receipt_path) if receipt_path.exists() else {'certificateSha256':cert,'backups':[],'backup_restoration_verified':False}
 if receipt['certificateSha256']!=cert:raise ReleaseError('Backup receipt belongs to another identity')
 if any(x['independenceId']==independence_id for x in receipt['backups']):raise ReleaseError('This independent medium is already recorded')
 password=getpass.getpass('Portable MAIN backup password (20+ chars; hidden): ')
 if len(password)<20 or password!=getpass.getpass('Repeat portable password: '):raise ReleaseError('Password validation failed; no backup made')
 local=local_password();key,_=key_paths()
 if key_info(key,local)['certificateSha256']!=cert:raise ReleaseError('Existing MAIN key differs from lock')
 env={**os.environ,'HOYO_MAIN_SOURCE_PASSWORD':local,'HOYO_MAIN_BACKUP_PASSWORD':password}
 run([keytool(),'-importkeystore','-noprompt','-srckeystore',key,'-srcstoretype','PKCS12','-srcalias',ALIAS,'-srcstorepass:env','HOYO_MAIN_SOURCE_PASSWORD','-destkeystore',destination,'-deststoretype','PKCS12','-destalias',ALIAS,'-deststorepass:env','HOYO_MAIN_BACKUP_PASSWORD','-destkeypass:env','HOYO_MAIN_BACKUP_PASSWORD'],env)
 if os.name=='nt':
  user=run(['whoami']).strip();run(['icacls',destination,'/inheritance:r','/grant:r',user+':F','*S-1-5-18:F'])
 info=key_info(destination,password)
 if info['certificateSha256']!=cert:raise ReleaseError('Backup certificate differs from lock')
 # Recovery needs ONLY the portable password and encrypted backup, not the DPAPI secret.
 with tempfile.TemporaryDirectory(prefix='main-restore-',dir=home()) as temp:
  temp=Path(temp);restored=temp/'restored.p12';shutil.copyfile(destination,restored)
  if key_info(restored,password)['certificateSha256']!=cert:raise ReleaseError('Restored PrivateKeyEntry differs')
  output=temp/'restoration-probe.apk';env={**os.environ,'HOYO_MAIN_BACKUP_PASSWORD':password}
  run(['java','-jar',jar,'sign','--ks',restored,'--ks-key-alias',ALIAS,'--ks-pass','env:HOYO_MAIN_BACKUP_PASSWORD','--key-pass','env:HOYO_MAIN_BACKUP_PASSWORD','--min-sdk-version','28','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',output,unsigned],env)
  verified=run(['java','-jar',jar,'verify','--verbose','--print-certs',output])
  if re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)',verified,re.M)!=[cert]:raise ReleaseError('Restored backup cannot sign with locked MAIN identity')
 row={'independenceId':independence_id,'destination':str(destination),'offHost':bool(off_host),'privateKeyEntry':True,'restoredAndTestSigned':True,'certificateSha256':cert,'portablePasswordOwnerHeld':True,'dpapiRequiredForRestoredCopy':False,'actualOtherComputerTested':False,'temporaryPrivateCopiesRemoved':True}
 receipt['backups'].append(row)
 from release_main import backup_ready
 receipt['backup_restoration_verified']=len(receipt['backups'])>=2 and any(x['offHost'] for x in receipt['backups'])
 receipt['ready']=backup_ready(receipt,cert)
 save(receipt_path,receipt)
 print(json.dumps({'independenceId':independence_id,'privateKeyEntry':True,'restoredAndTestSigned':True,'offHostOwnerAttested':off_host,'backup_restoration_verified':receipt['backup_restoration_verified'],'ready':receipt['ready'],'privateMaterialUploaded':False}))

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--destination',required=True,type=Path);p.add_argument('--independence-id',required=True);p.add_argument('--off-host',action='store_true',help='Owner attests this mounted location is genuinely independent/off-host storage, not another local folder');p.add_argument('--apksigner-jar',required=True,type=Path);p.add_argument('--unsigned',required=True,type=Path);a=p.parse_args()
 backup(a.destination,a.independence_id,a.off_host,a.apksigner_jar,a.unsigned)
if __name__=='__main__':
 try:main()
 except (ReleaseError,FileNotFoundError,ValueError,KeyError) as e:print(str(e),file=sys.stderr);sys.exit(1)
