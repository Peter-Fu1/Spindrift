import json,tempfile,unittest,sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parent))
import main_signing as s
from release_main import check_identity,backup_ready,check_scheme_outputs
from backup_main_signing import destination_ok
class MainReleaseGateTest(unittest.TestCase):
 def setUp(self):
  self.lock={'applicationId':s.APP_ID,'allowedVariant':'release','alias':s.ALIAS,'certificateSha256':'a'*64,'firstVersionCode':18,'signatureSchemes':['v2','v3']}
  self.meta={'package':s.APP_ID,'versionCode':18,'versionName':'0.1.16','label':'HoYoReminder','debuggable':False}
  self.ledger={'applicationId':s.APP_ID,'releases':[]}
 def test_first_main_candidate_identity(self):self.assertEqual(18,check_identity(self.meta,self.lock,self.ledger))
 def test_preview_and_debug_names_refused(self):
  for suffix in ('.debug','.preview','.release'):
   with self.subTest(suffix=suffix),self.assertRaises(s.ReleaseError):check_identity({**self.meta,'package':s.APP_ID+suffix},self.lock,self.ledger)
 def test_debuggable_and_preview_caption_refused(self):
  for change in ({'debuggable':True},{'testOnly':True},{'label':'HoYoReminder Preview'}):
   with self.subTest(change=change),self.assertRaises(s.ReleaseError):check_identity({**self.meta,**change},self.lock,self.ledger)
 def test_missing_or_preview_certificate_refused(self):
  for cert in (None,s.PREVIEW_CERT,'not-a-certificate'):
   with self.subTest(cert=cert),self.assertRaises(s.ReleaseError):s.validate_lock({**self.lock,'certificateSha256':cert})
 def test_scheme_channel_lock(self):
  for change in ({'allowedVariant':'preview'},{'signatureSchemes':['v3']},{'alias':'androiddebugkey'}):
   with self.subTest(change=change),self.assertRaises(s.ReleaseError):s.validate_lock({**self.lock,**change})
 def test_same_code_and_rollback_rejected_but_higher_allowed(self):
  self.ledger['releases']=[{'versionCode':18,'status':'released'}]
  for code in (14,17,18):
   with self.subTest(code=code),self.assertRaises(s.ReleaseError):check_identity({**self.meta,'versionCode':code},self.lock,self.ledger)
  self.assertEqual(19,check_identity({**self.meta,'versionCode':19,'versionName':'0.1.17','label':'Spindrift'},self.lock,self.ledger))
 def test_spindrift_name_from_code19_is_a_persistent_main_contract(self):
  meta={**self.meta,'versionCode':19,'versionName':'0.1.17','label':'Spindrift'}
  self.assertEqual(19,check_identity(meta,self.lock,self.ledger))
  for change in ({'label':'HoYoReminder'},{'label':'Spindrift Preview'},{'versionName':'0.1.16'}):
   with self.subTest(change=change),self.assertRaises(s.ReleaseError):check_identity({**meta,**change},self.lock,self.ledger)
  self.assertEqual(20,check_identity({**meta,'versionCode':20,'versionName':'0.1.18'},self.lock,self.ledger))
 def test_first_version_name_guard(self):
  with self.assertRaises(s.ReleaseError):check_identity({**self.meta,'versionName':'0.1.15'},self.lock,self.ledger)
 def test_wrong_ledger_is_refused(self):
  with self.assertRaises(s.ReleaseError):check_identity(self.meta,self.lock,{'applicationId':s.APP_ID+'.preview'})
 def test_backup_receipt_needs_two_distinct_media_offhost_and_private_recovery(self):
  row={'independenceId':'offline-usb','offHost':True,'privateKeyEntry':True,'restoredAndTestSigned':True,'certificateSha256':'a'*64}
  receipt={'certificateSha256':'a'*64,'backup_restoration_verified':True,'backups':[row,{**row,'independenceId':'private-disk','offHost':False}]}
  self.assertTrue(backup_ready(receipt,'a'*64))
  for altered in ([row],[row,row],[{**row,'offHost':False},{**row,'independenceId':'disk2','offHost':False}],[row,{**row,'independenceId':'disk2','privateKeyEntry':False}],[row,{**row,'independenceId':'disk2','certificateSha256':'b'*64}]):
   with self.subTest(rows=altered):self.assertFalse(backup_ready({**receipt,'backups':altered},'a'*64))
 def test_missing_key_does_not_create_replacement(self):
  with tempfile.TemporaryDirectory() as temp:
   key=Path(temp)/'absent.p12';password=Path(temp)/'absent.dpapi'
   with patch.object(s,'key_paths',return_value=(key,password)),self.assertRaises(s.ReleaseError):s.local_password()
   self.assertFalse(key.exists());self.assertFalse(password.exists())
 def test_backup_rejects_checkout_and_existing_file(self):
  with self.assertRaises(s.ReleaseError):destination_ok(s.ROOT/'private-main.p12')
  with tempfile.TemporaryDirectory() as temp:
   p=Path(temp)/'existing.p12';p.write_bytes(b'fixture')
   with self.assertRaises(s.ReleaseError):destination_ok(p)
 def test_existing_identity_cannot_be_initialized_again(self):
  with tempfile.TemporaryDirectory() as temp:
   key=Path(temp)/'exists.p12';key.write_bytes(b'fixture');password=Path(temp)/'absent.dpapi'
   with patch.object(s,'key_paths',return_value=(key,password)),patch.object(s,'load',return_value={}),self.assertRaises(s.ReleaseError):s.init()
   self.assertEqual(b'fixture',key.read_bytes())
class SchemeSelectionTest(unittest.TestCase):
 def test_api28_default_v3_and_independent_v2_are_accepted(self):
  common='Number of signers: 1\nSigner #1 certificate SHA-256 digest: '+'a'*64+'\n'
  check_scheme_outputs(common+'Verified using v2 scheme (APK Signature Scheme v2): false\nVerified using v3 scheme (APK Signature Scheme v3): true',common+'Verified using v2 scheme (APK Signature Scheme v2): true','a'*64)
 def test_v2_evidence_from_different_certificate_is_rejected(self):
  primary='Number of signers: 1\nSigner #1 certificate SHA-256 digest: '+'a'*64+'\nVerified using v3 scheme (APK Signature Scheme v3): true'
  v2='Number of signers: 1\nSigner #1 certificate SHA-256 digest: '+'b'*64+'\nVerified using v2 scheme (APK Signature Scheme v2): true'
  with self.assertRaises(s.ReleaseError):check_scheme_outputs(primary,v2,'a'*64)
 def test_missing_v2_is_rejected(self):
  text='Number of signers: 1\nSigner #1 certificate SHA-256 digest: '+'a'*64+'\nVerified using v3 scheme (APK Signature Scheme v3): true'
  with self.assertRaises(s.ReleaseError):check_scheme_outputs(text,text,'a'*64)
if __name__=='__main__':unittest.main()
