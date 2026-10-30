const fs=require('fs'),assert=require('assert/strict');const a=require('/tmp/iw-account-tooling/node_modules/miniprogram-automator');
let mp, oldToken,oldUser, tokenExists=false,userExists=false, changed=false;
const timer=setTimeout(()=>{console.error('Native real API verification timed out');process.exit(2)},70000);
async function wait(page,test){for(let n=0;n<80;n++){let d=await page.data();if(test(d))return d;await new Promise(r=>setTimeout(r,150))}throw Error('Timed out waiting for native page state')}
(async()=>{
 const acct=JSON.parse(fs.readFileSync('/Users/wangfarui/workspaces/wfr/iw-mixes_ai/.codex/local/acceptance-accounts.json')).accounts.find(x=>x.key==='member');
 const r=await fetch('http://127.0.0.1:18000/auth-service/login/password',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({account:acct.account,password:acct.password}),signal:AbortSignal.timeout(12000)});const response=await r.json();assert.equal(response.code,200);const u=response.data;assert.equal(u.roleType,acct.verifiedSystemRole);assert(u.tokenValue);
 mp=await a.connect({wsEndpoint:'ws://127.0.0.1:9437'});
 const keys=(await mp.callWxMethod('getStorageInfoSync')).keys;tokenExists=keys.includes('iwtoken');userExists=keys.includes('userInfo');
 oldToken=tokenExists?await mp.callWxMethod('getStorageSync','iwtoken'):null;oldUser=userExists?await mp.callWxMethod('getStorageSync','userInfo'):null;
 await mp.callWxMethod('setStorageSync','iwtoken',u.tokenValue);await mp.callWxMethod('setStorageSync','userInfo',u);changed=true;
 const p=await mp.reLaunch('/pagesPoints/points/task-plan-add');assert.equal(p.path,'pagesPoints/points/task-plan-add');
 let state=await wait(p,d=>!d.previewLoading&&d.preview);assert.equal(state.formData.calendarType,'SOLAR');
 await (await p.$('.segment[data-value="LUNAR"]')).tap();
 state=await wait(p,d=>d.formData.calendarType==='LUNAR'&&!d.converting&&!d.calendarLoading&&!d.previewLoading&&d.preview);assert(state.formData.lunarYear&&state.formData.lunarMonth&&state.formData.lunarDay);
 const pickers=await p.$$('picker');await pickers[0].trigger('change',{value:2});
 state=await wait(p,d=>d.formData.planCycle===3&&!d.previewLoading&&d.preview&&d.preview.ruleText.includes('农历月'));
 assert.equal(state.preview.occurrences.length,3);assert(state.preview.ruleText.includes('含闰月'));assert(state.preview.occurrences[0].planDateText.includes('公历'));
 await (await p.$('.form-input')).input('本地真实接口自验 🌙');
 await mp.pageScrollTo(1100);await p.waitFor(300);
 console.log('PASS AC-15 native simulator with real authenticated calendar/preview API; lunar selection and Chinese input; no business write');
 fs.writeFileSync('/tmp/iw-lunar-local-native-result.json',JSON.stringify({status:'PASS',realAuthenticatedAPI:true,calendar:'LUNAR',cycle:'MONTH',occurrences:state.preview.occurrences.length,fixtureWrites:0,sessionRestored:'pending'},null,2));
})().catch(e=>{console.error('FAIL',e.message);process.exitCode=1}).finally(async()=>{
 if(mp&&changed){try{if(tokenExists)await mp.callWxMethod('setStorageSync','iwtoken',oldToken);else await mp.callWxMethod('removeStorageSync','iwtoken');if(userExists)await mp.callWxMethod('setStorageSync','userInfo',oldUser);else await mp.callWxMethod('removeStorageSync','userInfo');const path='/tmp/iw-lunar-local-native-result.json';if(fs.existsSync(path)){const x=JSON.parse(fs.readFileSync(path));x.sessionRestored=true;fs.writeFileSync(path,JSON.stringify(x,null,2))}console.log('Original simulator session restored')}catch(e){console.error('Session restore failed:',e.message);process.exitCode=1}}
 if(mp)await mp.disconnect().catch(()=>{});clearTimeout(timer)
});
