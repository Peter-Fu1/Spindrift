import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeGames} from '../../web/normalization.mjs';
import {accountSummary,roleKey,roleIdentity,roleSnapshot,refreshStatus,refreshRole,browserIsolation} from '../../web/shared/platform-contract.mjs';
const now=1800000000000;
const key={accountId:'111',game:'genshin',roleUid:'100000001',server:'cn_gf01'};
const input={id:'genshin',roleUid:key.roleUid,status:'ok',current:100,maximum:200,interval:480,taskState:'done',dailyCurrent:4,dailyMax:4,updated:now};
test('shared contract reuses Windows normalization with explicit account/role/server scope',()=>{
 const s=roleSnapshot(key,input,{now});assert.deepEqual(s.note,normalizeGames([input],now)[0]);
 assert.equal(s.lastSuccessfulAt,now);assert.equal(s.recoveryAt,now+100*480000);assert.equal(s.note.taskState,'done');
 assert.throws(()=>roleKey({...key,server:'天空岛'}),/INVALID_ROLE_KEY/);
 assert.notEqual(roleIdentity(key),roleIdentity({...key,accountId:'222'}));
 assert.notEqual(roleIdentity(key),roleIdentity({...key,server:'cn_qd01'}));
});
test('cached failure keeps the last success and estimates resource without changing task truth',()=>{
 const old=roleSnapshot(key,input,{now}),s=roleSnapshot(key,{...input,status:'auth_expired'},{previous:old,now:now+480000,verification:'expired'});
 assert.equal(s.lastSuccessfulAt,now);assert.equal(s.estimatedCurrent,101);assert.equal(s.auth,'expired');assert.equal(s.stale,true);
 assert.equal(s.note.taskState,'done');assert.equal(s.note.updated,now);assert.equal(s.verification,'expired');
 assert.throws(()=>roleSnapshot({...key,accountId:'222'},input,{previous:old,now}),/CROSS_ROLE_CACHE/);
});
test('all three games use one model, unknown recovery remains unknown, full resource stays capped',()=>{
 for(const game of ['genshin','starrail','zenless']){const s=roleSnapshot({...key,game},{...input,id:game},{now});assert.equal(s.note.id,game);}
 assert.equal(roleSnapshot(key,{...input,interval:null},{now}).recoveryAt,null);
 assert.equal(roleSnapshot(key,{...input,current:200},{now:now+99999999}).estimatedCurrent,200);
 assert.equal(roleSnapshot(key,{...input,updated:undefined},{now}).lastSuccessfulAt,null);
 assert.throws(()=>roleSnapshot(key,{...input,roleUid:'999'},{now}),/CROSS_ROLE_NOTE/);
});
test('partial refresh results, account risk, verification and cancellation remain independent',async()=>{
 const risk=roleSnapshot(key,{...input,status:'account_risk',retcode:5003},{now,verification:'unavailable'});
 assert.equal(risk.risk,'account_risk');assert.equal(risk.note,null);
 const job=refreshStatus({refreshId:'a'.repeat(32),status:'completed',roles:[{...key,state:'failed',error:'account_risk',retcode:5003},{...key,game:'starrail',state:'success'}]});
 assert.deepEqual(job.roles.map(r=>r.state),['failed','success']);
 let calls=0;const controller=new AbortController();controller.abort();
 await assert.rejects(refreshRole({refresh:()=>calls++},key,{signal:controller.signal}),{name:'AbortError'});assert.equal(calls,0);
 const late=new AbortController();await assert.rejects(refreshRole({refresh:async()=>{late.abort();return roleSnapshot(key,input,{now});}},key,{signal:late.signal}),{name:'AbortError'});
 await assert.rejects(refreshRole({refresh:async()=>({...risk,key:{...key,accountId:'222'}})},key),/INVALID_ROLE_RESULT/);
});
test('public account metadata cannot project secrets and isolation fails closed without a capability',()=>{
 const a=accountSummary({id:'111',label:'fixture',cookies:{secret:'private'},token:'private'});assert.doesNotMatch(JSON.stringify(a),/private/);
 assert.equal(browserIsolation({multiProfile:true}),'named_profile');assert.equal(browserIsolation({isolatedProcess:true}),'process_directory');assert.equal(browserIsolation(),'unavailable');
});
