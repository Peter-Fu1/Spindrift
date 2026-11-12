import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),{estimateResource}=require('../../web/resource-estimate.js'),ui=require('../../web/ui-model.js');
const t=1800000000000;
const snap=(game='genshin')=>({version:1,source:'server_snapshot',serverResourceValue:198,resourceMax:200,observedAtUtc:t,periodSeconds:game==='genshin'?480:360,remainingSeconds:game==='genshin'?500:400,phaseAvailable:true});
for(const game of ['genshin','starrail','zenless'])test(game+' phase, exact first tick, multiple periods, cap and immutable restart anchor',()=>{
 const s=snap(game),before=JSON.stringify(s),p=s.periodSeconds,phase=s.remainingSeconds-p;
 assert.equal(estimateResource(s,t+(phase-.001)*1000).estimatedValue,198);
 assert.equal(estimateResource(s,t+phase*1000).estimatedValue,199);
 assert.equal(estimateResource(s,t+s.remainingSeconds*1000).estimatedValue,200);
 assert.equal(estimateResource(s,t+30*86400000).estimatedValue,200);
 assert.equal(estimateResource(JSON.parse(before),t+phase*1000).estimatedValue,199);
 assert.equal(JSON.stringify(s),before);
});
test('clock rollback/jump, malformed phase, invalid max/timestamps and missing snapshots fail predictably',()=>{
 assert.equal(estimateResource(null,t).status,'no_snapshot');
 assert.equal(estimateResource(snap(),t-1).status,'clock_rollback');
 assert.equal(estimateResource(snap(),t+367*86400000).status,'clock_jump');
 assert.equal(estimateResource(snap(),t,300).status,'invalid_snapshot');
 for(const remainingSeconds of [0,-1,10000,NaN])assert.equal(estimateResource({...snap(),remainingSeconds},t).status,'phase_unavailable');
 assert.equal(estimateResource({...snap(),observedAtUtc:0},t).status,'invalid_snapshot');
 assert.equal(estimateResource({...snap(),serverResourceValue:200},t).estimatedValue,200);
});
const accounts=['A','B','C'].map(id=>({id,roles:(id==='B'?['genshin']:['genshin','starrail','zenless']).map((game,i)=>({game,uid:id+i,serverId:'server'}))}));
test('joint selection is an intersection of stable owner/game/UID/server and preserves zero',()=>{
 let p=ui.normalize({},accounts);assert.equal(ui.targets(accounts,p).length,7);
 p.accountIds=['A','B'];p.roleKeys=[ui.roleKey(accounts[1],accounts[1].roles[0]),ui.roleKey(accounts[2],accounts[2].roles[0])];
 assert.deepEqual(ui.targets(accounts,p),[ui.roleKey(accounts[1],accounts[1].roles[0])]);
 p.accountIds=[];assert.deepEqual(ui.targets(accounts,p),[]);
 p.accountIds=['A'];p.roleKeys=[];assert.deepEqual(ui.targets(accounts,p),[]);
});
test('sorting, new/deleted accounts and role exclusions survive serialization independently from expansion/selection',()=>{
 let p=ui.normalize({order:['C','A','B'],roleKeys:[],expanded:['A'],mode:'local'},accounts);
 assert.deepEqual(p.order,['C','A','B']);assert.deepEqual(ui.move(p.order,'B',0),['B','C','A']);
 assert.deepEqual(ui.normalize(JSON.parse(JSON.stringify(p)),accounts).roleKeys,[]);
 const changed=accounts.filter(a=>a.id!=='B').concat({id:'D',roles:[]});
 p=ui.normalize(p,changed);assert.deepEqual(p.order,['C','A','D']);assert.deepEqual(p.expanded,['A']);assert.equal(p.mode,'local');
 assert.deepEqual(ui.targets(accounts,{...ui.normalize({},accounts),expanded:[]}),ui.targets(accounts,ui.normalize({},accounts)));
});
