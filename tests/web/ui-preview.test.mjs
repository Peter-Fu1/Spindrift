import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync,readdirSync} from 'node:fs';
const require=createRequire(import.meta.url),fixtures=require('../../tools/ui-preview/fixtures.js');
const text=p=>readFileSync(new URL(p,new URL('../../',import.meta.url)),'utf8');
const now=Date.UTC(2026,9,24,2);
test('preview scenarios are synthetic, independent and contain no credential or device payload',()=>{
 for(const scenario of fixtures.scenarios){
  const x=fixtures.create(scenario,now),y=fixtures.create(scenario,now);
  assert.equal(x.scenario,scenario);
  for(const a of x.state.accounts){assert.match(a.id,/^DEMO-/);for(const r of a.roles){assert.match(r.uid,/^DEMO-/);assert.ok(x.ledger.roles[fixtures.key(a,r)+'|CN']);}}
  assert.doesNotMatch(JSON.stringify(x),/"(?:cookie|token|deviceFP|deviceId|seed|pending|fingerprint)"\s*:/i);
  if(x.state.accounts.length){x.state.accounts[0].label='changed';assert.notEqual(y.state.accounts[0].label,'changed');}
 }
 assert.equal(fixtures.create('empty',now).state.accounts.length,0);
 assert.equal(fixtures.create('genshin-only',now).state.accounts[0].roles.length,1);
 assert.deepEqual(fixtures.create('errors',now).state.accounts[0].games.map(g=>g.retcode),[5003,1034,10041]);
});
test('calendar fixtures stay internally consistent across leap years and month changes',()=>{
 for(const date of ['2024-02-29','2026-01-01','2026-04-30']){
  const x=fixtures.create('checkin',Date.parse(date+'T02:00:00Z'));
  for(const r of Object.values(x.ledger.roles)){assert.equal(r.info.today,date);assert.equal(r.home.month,date.slice(0,7));assert.equal(r.home.days,r.home.awards.length);assert.ok(r.info.total<=Number(date.slice(-2)));}
 }
});
test('formal entry and APK asset whitelist never include development preview files',()=>{
 assert.doesNotMatch(text('./web/index.html'),/ui-preview|fixtures\.js|preview=|HoYoPreview/);
 const gradle=text('./app/build.gradle.kts');
 assert.match(gradle,/val webAssets by tasks.registering\(Sync::class\)/);
 const include=gradle.match(/from\(rootProject.file\("web"\)\) \{ include\(([^\n]+)\) \}/)?.[1];
 assert.ok(include);assert.doesNotMatch(include,/ui-preview|tools|\*/);
 assert.doesNotMatch(text('./web/platform-adapter.js'),/preview|fixtures|location\.search/i);
});
test('preview launcher is loopback-only and replaces only adapter in fetched formal HTML',()=>{
 const loader=text('./tools/ui-preview/loader.js'),adapter=text('./tools/ui-preview/adapter.js');
 assert.match(loader,/\['localhost','127\.0\.0\.1','\[::1\]'\]\.includes\(location\.hostname\)/);
 assert.match(loader,/params\.get\('preview'\)==='1'/);
 assert.match(loader,/new URL\('index\.html',root\)/);
 assert.match(loader,/connect-src 'none'/);assert.match(loader,/worker-src 'none'/);
 assert.match(loader,/credentials:'omit'/);assert.match(adapter,/parent===window/);assert.match(adapter,/window\.HoYoNative/);
 assert.doesNotMatch(adapter,/\bfetch\s*\(|XMLHttpRequest|indexedDB|document\.cookie|HoYoNative\s*\./);
 assert.match(adapter,/return blocked\(\)/);
 assert.match(adapter,/Object\.defineProperty\(window,'localStorage'/);
});
