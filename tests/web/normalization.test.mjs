import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeGames} from '../../web/normalization.mjs';

test('normalizes supported games and strips unrelated upstream fields',()=>{
  const games=normalizeGames([{id:'genshin',current:120,maximum:200,interval:480,taskState:'done',cookie:'secret',uid:'123456789'}],1000);
  assert.equal(games.length,1);
  assert.equal(games[0].current,120);
  assert.equal(games[0].max,200);
  assert.equal(games[0].task,'已完成');
  assert.equal('cookie' in games[0],false);
  assert.equal('uid' in games[0],false);
});

test('unknown and malformed values stay unknown instead of becoming zero',()=>{
  const [game]=normalizeGames([{id:'starrail',current:-3,maximum:300,interval:0,taskState:'mystery'}],2000);
  assert.equal(game.current,null);
  assert.equal(game.interval,null);
  assert.equal(game.taskState,'unknown');
  assert.equal(game.updated,2000);
});

test('drops unsupported games and rejects malformed top-level response',()=>{
  assert.deepEqual(normalizeGames([{id:'unknown',current:1}]),[]);
  assert.throws(()=>normalizeGames({current:1}),{message:'UNSUPPORTED_RESPONSE'});
});

test('failed responses cannot claim task completion or leak arbitrary labels',()=>{
 const [game]=normalizeGames([{id:'genshin',status:'risk_control_required',taskState:'done',taskLabel:'secret'}]);
 assert.equal(game.taskState,'unknown');
 assert.equal(game.task,'状态未知');
});

test('cached success retains its original sample time and only a safe cache flag',()=>{
 const [game]=normalizeGames([{id:'genshin',status:'ok',current:20,maximum:200,
   updated:1234,cached:true,device_fp:'private'}],9000);
 assert.equal(game.updated,1234);
 assert.equal(game.cached,true);
 assert.equal('device_fp' in game,false);
});

test('WebView source is allowed only for successful validated notes',()=>{
 const [ok,failed]=normalizeGames([{id:'zenless',status:'ok',source:'miyoushe_webview',current:20,max:240},{id:'zenless',status:'request_invalid',source:'miyoushe_webview',retcode:-10001,cookie:'private'}]);
 assert.equal(ok.source,'miyoushe_webview');assert.equal(failed.source,'sdk');assert.equal(failed.retcode,-10001);assert.equal(failed.status,'request_invalid');assert.equal('cookie' in failed,false);
});
