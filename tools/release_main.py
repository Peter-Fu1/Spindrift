"""Local MAIN release gate: unsigned CI APK -> alignment receipt -> persistent key -> verified APK.
Candidate signing is explicit and cannot be labelled ready or enter the published ledger.
"""
from pathlib import Path
import argparse,sys,os,re,json,zipfile,datetime
from main_signing import *
from apk_metadata import apk_metadata
from verify_apk_ui import verify_apk

def check_identity(meta,lock,ledger):
 validate_lock(lock)
 if ledger.get('applicationId')!=APP_ID:raise ReleaseError('Wrong release ledger')
 code=int(meta.get('versionCode',0))
 expected_label='HoYoReminder' if code==18 else 'Spindrift'
 if meta.get('package')!=APP_ID or meta.get('debuggable') or meta.get('testOnly') or meta.get('label')!=expected_label:raise ReleaseError('Wrong package, launcher name or debuggable MAIN artifact')
 previous=max([int(x['versionCode']) for x in ledger.get('releases',[]) if x.get('status')=='released']+[lock['firstVersionCode']-1])
 if code<=previous:raise ReleaseError('MAIN versionCode must strictly exceed published maximum')
 if code==19 and meta.get('versionName')!='0.1.17':raise ReleaseError('Main 0.1.17 version contract mismatch')
 if code==18 and meta.get('versionName')!='0.1.16':raise ReleaseError('First MAIN version contract mismatch')
 return code

def backup_ready(receipt,cert):
 rows=receipt.get('backups',[])
 return receipt.get('certificateSha256')==cert and receipt.get('backup_restoration_verified') is True and len(rows)>=2 and len({r.get('independenceId') for r in rows})>=2 and all(r.get('privateKeyEntry') is True and r.get('restoredAndTestSigned') is True and r.get('certificateSha256')==cert for r in rows) and any(r.get('offHost') is True for r in rows)

def check_scheme_outputs(primary,v2,cert):
 for text in (primary,v2):
  if re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)',text,re.M)!=[cert] or 'Number of signers: 1' not in text:raise ReleaseError('MAIN certificate/signer verification failed')
 if 'Verified using v3 scheme (APK Signature Scheme v3): true' not in primary or 'Verified using v2 scheme (APK Signature Scheme v2): true' not in v2:raise ReleaseError('MAIN V2/V3 independent verification failed')

def sign(unsigned,jar,alignment,output_dir,candidate=False,source_root=ROOT):
 lock=load(LOCK);cert=validate_lock(lock);ledger=load(LEDGER)
 source_root=Path(source_root).resolve()
 if run(['git','-C',source_root,'rev-parse','--path-format=absolute','--git-common-dir']).strip()!=run(['git','-C',ROOT,'rev-parse','--path-format=absolute','--git-common-dir']).strip():raise ReleaseError('Build source must be a worktree of this repository')
 if run(['git','-C',source_root,'diff','--name-only','HEAD']).strip():raise ReleaseError('Tracked sources changed; commit exact build inputs first')
 revision=run(['git','-C',source_root,'rev-parse','HEAD']).strip()
 signing_revision=run(['git','-C',ROOT,'rev-parse','HEAD']).strip()
 unsigned=Path(unsigned).resolve();output_dir=Path(output_dir).resolve();jar=Path(jar).resolve()
 meta=apk_metadata(unsigned);code=check_identity(meta,lock,ledger)
 if meta.get('minSdkVersion')!=28 or meta.get('targetSdkVersion')!=35:raise ReleaseError('MAIN SDK contract changed')
 if subprocess.run(['java','-jar',str(jar),'verify',str(unsigned)],stdout=subprocess.PIPE,stderr=subprocess.PIPE).returncode==0:raise ReleaseError('Expected unsigned CI MAIN input, not an already signed APK')
 if not verify_apk(unsigned,source_root)['passed']:raise ReleaseError('APK web assets do not match checkout')
 with zipfile.ZipFile(unsigned) as z:
  if not any(revision.encode() in z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex')):raise ReleaseError('Unsigned APK source revision differs from checkout')
 alignment=load(alignment)
 if alignment.get('sourceRevision')!=revision or alignment.get('apkSha256')!=sha(unsigned) or alignment.get('zipalignVerified') is not True:raise ReleaseError('Missing matching CI zipalign pre-sign verification receipt')
 key,_=key_paths();password=local_password();info=key_info(key,password)
 if info['certificateSha256']!=cert:raise ReleaseError('MAIN key certificate differs from public lock')
 receipt_path=home()/'main-backup-receipt.json'
 backups=load(receipt_path) if receipt_path.is_file() else {}
 ready=backup_ready(backups,cert)
 if not ready and not candidate:raise ReleaseError('Two independent backups, off-host copy and restore drill required; explicit candidate mode only')
 name=f"Spindrift-Android-{meta['versionName']}-release"+('-candidate' if candidate else '')+'.apk'
 output_dir.mkdir(parents=True,exist_ok=True);output=output_dir/name
 temporary=output_dir/(name+'.incomplete')
 if output.exists() or temporary.exists():raise ReleaseError('Output already exists; refusing overwrite')
 env={**os.environ,'HOYO_MAIN_KEY_PASSWORD':password}
 run(['java','-jar',jar,'sign','--ks',key,'--ks-key-alias',ALIAS,'--ks-pass','env:HOYO_MAIN_KEY_PASSWORD','--key-pass','env:HOYO_MAIN_KEY_PASSWORD','--min-sdk-version','28','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',temporary,unsigned],env)
 verification=run(['java','-jar',jar,'verify','--verbose','--print-certs',temporary])
 v2_verification=run(['java','-jar',jar,'verify','--min-sdk-version','24','--max-sdk-version','27','--verbose','--print-certs',temporary])
 check_scheme_outputs(verification,v2_verification,cert)
 if apk_metadata(temporary)!=meta or not verify_apk(temporary,source_root)['passed']:raise ReleaseError('Signed identity or resources changed')
 temporary.rename(output)
 report={'applicationId':APP_ID,'versionName':meta['versionName'],'versionCode':code,'debuggable':False,'label':meta['label'],'certificateSha256':cert,'privateKeyEntry':True,'keyId':lock['keyId'],'sourceRevision':revision,'signingToolsRevision':signing_revision,'apk':name,'bytes':output.stat().st_size,'apkSha256':sha(output),'unsignedSha256':sha(unsigned),'zipalignVerifiedBeforeSigning':True,'schemes':['v2','v3'],'signers':1,'ready':ready and not candidate,'status':'candidate' if candidate else 'released','backup_restoration_verified':ready,'privateMaterialUploaded':False,'realPhoneTested':False,'sameCertificateUpgradeVerified':False,'wrongCertificateRejectionVerified':False,'legacyDataMigrated':False,'date':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 (output_dir/'apksigner-verification.txt').write_text(verification,encoding='utf-8')
 (output_dir/'apksigner-v2-verification.txt').write_text(v2_verification,encoding='utf-8')
 save(output_dir/'release-manifest.json',report)
 (output_dir/'SHA256SUMS.txt').write_text(report['apkSha256']+'  '+name+'\n',encoding='ascii')
 save(output_dir/'apk-ui-verification.json',verify_apk(output,source_root))
 if not candidate:
  ledger['releases'].append({k:report[k] for k in ['applicationId','versionName','versionCode','certificateSha256','apkSha256','sourceRevision','date','status']});save(LEDGER,ledger)
 print(json.dumps(report))
 return report

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--unsigned',required=True,type=Path);p.add_argument('--apksigner-jar',required=True,type=Path);p.add_argument('--alignment-receipt',required=True,type=Path);p.add_argument('--output-dir',required=True,type=Path);p.add_argument('--candidate',action='store_true');p.add_argument('--source-root',type=Path,default=ROOT);a=p.parse_args()
 sign(a.unsigned,a.apksigner_jar,a.alignment_receipt,a.output_dir,a.candidate,a.source_root)
if __name__=='__main__':
 try:main()
 except (ReleaseError,FileNotFoundError,ValueError,KeyError) as e:print(str(e),file=sys.stderr);sys.exit(1)
