package io.github.peterfu.hoyoreminder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(val id:String,val metadata:JSONObject,var secret:JSONObject?,val revision:String=UUID.randomUUID().toString())
interface AccountPersistence {
 fun load():List<Account>
 fun selected():String?
 fun save(account:Account,select:Boolean=false)
 fun select(id:String?)
 fun remove(id:String,selected:String?)
 fun installationId():String
 fun loadCheckin():JSONObject = JSONObject()
 fun saveCheckin(value:JSONObject){}
 fun loadUi():JSONObject = JSONObject()
 fun saveUi(value:JSONObject){}
}
class CredentialVault {
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (store.getKey("hoyoreminder.credentials.v1",null) as? SecretKey)?.let{return it}
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("hoyoreminder.credentials.v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
  }.generateKey()
 }
 @Synchronized fun encrypt(id:String,value:JSONObject):ByteArray {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD("hoyo:$id:v1".toByteArray())
  return obj("v" to 1,"iv" to Base64.encodeToString(cipher.iv,Base64.NO_WRAP),"data" to Base64.encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
 }
 @Synchronized fun decrypt(id:String,bytes:ByteArray):JSONObject {
  val envelope=JSONObject(String(bytes,Charsets.UTF_8));require(envelope.getInt("v")==1)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));cipher.updateAAD("hoyo:$id:v1".toByteArray())
  return JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8))
 }
}
class AccountStore(context:Context,databaseName:String="hoyoreminder.db"):SQLiteOpenHelper(context,databaseName,null,1),AccountPersistence {
 private val vault=CredentialVault()
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, metadata TEXT NOT NULL, secret BLOB NOT NULL)")
  db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value BLOB)")
  db.execSQL("CREATE TABLE pending_profiles(name TEXT PRIMARY KEY)")
 }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw IllegalStateException("Explicit migration required")}
 @Synchronized override fun load():List<Account> {
  val result=mutableListOf<Account>()
  readableDatabase.rawQuery("SELECT id,metadata,secret FROM accounts ORDER BY rowid",null).use{c->while(c.moveToNext()){
   val id=c.getString(0);val metadata=JSONObject(c.getString(1));val secret=runCatching{vault.decrypt(id,c.getBlob(2))}.getOrNull()
   if(secret==null)metadata.put("auth","expired")
   metadata.optJSONArray("games")?.objects()?.forEach{it.put("cached",true)}
   result.add(Account(id,metadata,secret))
  }};return result
 }
 @Synchronized override fun selected():String?=readableDatabase.rawQuery("SELECT value FROM settings WHERE key='selected'",null).use{if(it.moveToFirst())it.getString(0) else null}
 @Synchronized override fun save(account:Account,select:Boolean) {
  val encrypted=try{vault.encrypt(account.id,account.secret?:throw PublicError("AUTH_EXPIRED"))}catch(e:Exception){throw PublicError("SAVE_FAILED")}
  val db=writableDatabase;db.beginTransaction()
  try{db.execSQL("INSERT OR REPLACE INTO accounts VALUES(?,?,?)",arrayOf(account.id,account.metadata.toString(),encrypted));if(select)db.execSQL("INSERT OR REPLACE INTO settings VALUES('selected',?)",arrayOf(account.id));db.setTransactionSuccessful()}catch(e:Exception){throw PublicError("SAVE_FAILED")}finally{db.endTransaction()}
 }
 @Synchronized override fun select(id:String?){writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES('selected',?)",arrayOf(id))}
 @Synchronized override fun remove(id:String,selected:String?) {
  val db=writableDatabase;val profile=load().firstOrNull{it.id==id}?.secret?.optString("profileKey")
  db.beginTransaction();try{db.execSQL("DELETE FROM accounts WHERE id=?",arrayOf(id))
   val checkin=loadCheckin();checkin.optJSONObject("roles")?.let{rows->rows.keys().asSequence().toList().filter{it.startsWith(id+"|")}.forEach{rows.remove(it)}}
   db.execSQL("INSERT OR REPLACE INTO settings VALUES('checkin_v1',?)",arrayOf(checkin.toString()))
   ;db.execSQL("INSERT OR REPLACE INTO settings VALUES('selected',?)",arrayOf(selected));if(!profile.isNullOrBlank())db.execSQL("INSERT OR IGNORE INTO pending_profiles VALUES(?)",arrayOf(profile));db.setTransactionSuccessful()}finally{db.endTransaction()}
 }
 @Synchronized override fun loadCheckin():JSONObject=readableDatabase.rawQuery("SELECT value FROM settings WHERE key='checkin_v1'",null).use{c->if(c.moveToFirst())runCatching{JSONObject(c.getString(0))}.getOrDefault(JSONObject()) else JSONObject()}
 @Synchronized override fun saveCheckin(value:JSONObject){writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES('checkin_v1',?)",arrayOf(value.toString()))}
 @Synchronized override fun loadUi():JSONObject=readableDatabase.rawQuery("SELECT value FROM settings WHERE key='ui_preferences_v1'",null).use{c->if(c.moveToFirst())runCatching{JSONObject(c.getString(0))}.getOrDefault(JSONObject()) else JSONObject()}
 @Synchronized override fun saveUi(value:JSONObject){writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES('ui_preferences_v1',?)",arrayOf(value.toString()))}
 @Synchronized override fun installationId():String {
  readableDatabase.rawQuery("SELECT value FROM settings WHERE key='installation'",null).use{c->if(c.moveToFirst())runCatching{vault.decrypt("installation",c.getBlob(0)).getString("deviceId")}.getOrNull()?.let{return it}}
  val id=UUID.randomUUID().toString();val encrypted=vault.encrypt("installation",obj("deviceId" to id));writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES('installation',?)",arrayOf(encrypted));return id
 }
 @Synchronized fun pendingProfiles():List<String> = readableDatabase.rawQuery("SELECT name FROM pending_profiles",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
 @Synchronized fun profileDeleted(name:String){writableDatabase.execSQL("DELETE FROM pending_profiles WHERE name=?",arrayOf(name))}
}
