package io.github.peterfu.hoyoreminder

/** Public widget shell, shared with WebView instrumentation; no account session. */
object CaptchaPage {
 fun html(c:NoteChallenge):String {
  val encoded=java.util.Base64.getEncoder().encodeToString(c.config.toString().toByteArray(Charsets.UTF_8))
  val session=java.util.Base64.getEncoder().encodeToString(c.session.toByteArray(Charsets.UTF_8))
  return """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">
<div id="captcha"></div><p id="status">正在加载官方验证组件…</p>
<script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script><script src="https://static.geetest.com/v4/gt4.js"></script>
<script>
const config=JSON.parse(atob('$encoded')),session=atob('$session');
const send=v=>CaptchaResult.postMessage(typeof v==='string'?v:JSON.stringify(v));
const ready=obj=>{obj.appendTo('#captcha');obj.onSuccess(()=>send(obj.getValidate()));obj.onClose(()=>send('cancel'));obj.onError(()=>send({event:'error'}));obj.onReady(()=>{send({event:'ready'});document.getElementById('status').textContent='';config.challenge?obj.verify():obj.showCaptcha();});};
try{if(config.challenge)initGeetest({gt:config.gt,challenge:config.challenge,offline:false,new_captcha:true,product:'popup',https:true},ready);
else initGeetest4({captchaId:config.gt,riskType:config.risk_type,product:'popup',lang:'zho',https:true,protocol:'https://',userInfo:JSON.stringify({session_id:session})},ready);}catch(e){send({event:'error'});}
</script>"""
 }
}
