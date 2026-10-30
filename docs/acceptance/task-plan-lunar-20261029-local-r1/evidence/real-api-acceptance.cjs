const fs=require('fs'), assert=require('assert/strict');
const accounts=JSON.parse(fs.readFileSync('/Users/wangfarui/workspaces/wfr/iw-mixes_ai/.codex/local/acceptance-accounts.json','utf8')).accounts;
const base='http://127.0.0.1:18000', prefix='LUNAR-AC-20261029-'+Date.now().toString(36)+'-';
const results=[], plans=[], tasks=[]; let member, other;
async function raw(method,url,body,token){
 const opts={method,headers:{'Content-Type':'application/json',...(token?{iwtoken:token}:{})},signal:AbortSignal.timeout(15000)};
 if(body!==undefined)opts.body=JSON.stringify(body);
 const res=await fetch(base+url,opts); const text=await res.text();try{return JSON.parse(text)}catch{return {code:res.status,message:'non-JSON response'}}
}
async function api(method,url,body,token){const r=await raw(method,url,body,token); if(r.code!==200)throw Error(method+' '+url.split('?')[0]+' code='+r.code+' message='+(r.message||r.msg||''));return r.data}
function pass(id,note){results.push({id,status:'PASS',note}); console.log('PASS',id,note)}
async function login(key){let a=accounts.find(x=>x.key===key);assert(a);let u=await api('POST','/auth-service/login/password',{account:a.account,password:a.password});assert(u.tokenValue);assert.equal(u.roleType,a.verifiedSystemRole);let f=await api('GET','/auth-service/family/group/myGroup',undefined,u.tokenValue);let role=f&&f.currentUserRole||null;assert.equal(role,a.verifiedFamilyRole);return u}
async function add(body){const id=await api('POST','/points-service/points/task/plan/add',body,member.tokenValue);assert(Number(id)>0);plans.push(id);return id}
const route='/points-service/points/task/plan';
function plus(date,n){const d=new Date(date+'T00:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10)}
async function main(){
 try {
  assert.notEqual((await raw('GET',route+'/calendar?year=2028')).code,200);pass('AC-12a','unauthenticated calendar is rejected');
  member=await login('member');other=await login('personal');pass('LOGIN','two real local logins, roles and family relationship match recorded identities');
  const m=await api('GET',route+'/calendar?year=2028',undefined,member.tokenValue);const leap=m.find(x=>x.leap);assert(leap);const normal=m.find(x=>x.month===leap.month&&!x.leap);assert(normal);
  const rule={calendarType:'LUNAR',planCycle:3,lunarYear:2028,lunarMonth:leap.month,lunarDay:1,leapMonth:false,remindDays:0,deadlineDays:0};
  const preview=await api('POST',route+'/preview',rule,member.tokenValue);assert.equal(preview.occurrences.length,3);assert(!preview.occurrences[0].planDateText.includes('闰'));assert(preview.occurrences[1].planDateText.includes('闰'));assert(!preview.occurrences[2].planDateText.includes('闰'));pass('AC-04','three real preview periods include regular month and matching leap month');
  const select=await api('POST',route+'/convertDate',{calendarType:'LUNAR',lunarYear:2028,lunarMonth:leap.month,lunarDay:1,leapMonth:true},member.tokenValue);
  const round=await api('POST',route+'/convertDate',{calendarType:'SOLAR',planDate:select.solarDate},member.tokenValue);assert.equal(round.leapMonth,true);assert.equal(round.lunarMonth,leap.month);assert.equal(round.lunarDay,1);pass('AC-03','leap date lunar -> solar -> lunar retains the actual day');
  const id=await add({...rule,taskName:prefix+'月计划',rewardPoints:0,punishPoints:0,bookkeepingEnabled:false});
  let initial=await api('GET',route+'/detail?id='+id,undefined,member.tokenValue);assert.equal(initial.calendarType,'LUNAR');assert.equal(initial.lunarMonth,leap.month);assert.equal(initial.leapMonth,false);
  await api('PUT',route+'/update',{...rule,id,taskName:prefix+'月计划编辑',rewardPoints:0,punishPoints:0,bookkeepingEnabled:false},member.tokenValue);
  let updated=await api('GET',route+'/detail?id='+id,undefined,member.tokenValue);assert.equal(updated.planDate,initial.planDate);assert.equal(updated.anchorDate,initial.anchorDate);pass('AC-02','metadata edit preserves the cursor and original anchor');
  await api('PUT',route+'/update',{id,taskName:prefix+'旧客户端编辑',planCycle:3,planDate:updated.planDate,remindDays:0,deadlineDays:0,rewardPoints:0,punishPoints:0},member.tokenValue);
  updated=await api('GET',route+'/detail?id='+id,undefined,member.tokenValue);assert.equal(updated.calendarType,'LUNAR');assert.equal(updated.anchorDate,initial.anchorDate);pass('COMPAT','legacy update without calendar preserves lunar rule');
  for(const [method,path,body] of [['GET','/detail?id='+id,undefined],['POST','/preview',{...rule,id}],['POST','/generateNow',{id,requestId:prefix+'access_check_0001'}]]){
   let x=await raw(method,route+path,body,other.tokenValue);assert.notEqual(x.code,200,method+' access should be denied');
  } pass('AC-12b','another real local user cannot read, preview or generate the plan');
  await api('PUT',route+'/updateStatus',{id,status:0},member.tokenValue);
  await api('PUT',route+'/update',{...rule,lunarDay:2,id,taskName:prefix+'禁用计划',rewardPoints:0,punishPoints:0,bookkeepingEnabled:false},member.tokenValue);
  updated=await api('GET',route+'/detail?id='+id,undefined,member.tokenValue);assert.equal(updated.status,0);pass('AC-09','disabled plan remains disabled on date edit');
  const now=preview.serverToday;const d=plus(now,2);const lunar=await api('POST',route+'/convertDate',{calendarType:'SOLAR',planDate:d},member.tokenValue);
  const imminent={calendarType:'LUNAR',planCycle:4,lunarYear:lunar.lunarYear,lunarMonth:lunar.lunarMonth,lunarDay:lunar.lunarDay,leapMonth:lunar.leapMonth,remindDays:3,deadlineDays:2};
  const due=await add({...imminent,taskName:prefix+'提前生成',rewardPoints:0,punishPoints:0,bookkeepingEnabled:false});
  const dueInfo=await api('GET',route+'/detail?id='+due,undefined,member.tokenValue);assert.notEqual(dueInfo.planDate,d);assert.equal(dueInfo.anchorDate,d);
  const taskPage=await api('POST','/points-service/points/task/basics/page',{taskName:prefix+'提前生成',currentPage:1,pageSize:20},member.tokenValue);const rec=(taskPage.records||[]).filter(x=>x.taskName===prefix+'提前生成');assert.equal(rec.length,1);tasks.push(rec[0].id);assert.equal(String(rec[0].deadlineDate).slice(0,10),plus(d,2));pass('AC-07','saved reminder-window plan generated one task and advanced cursor, deadline correct');
  const requestId='lunar_manual_'+Date.now()+'_001';const one=await api('POST',route+'/generateNow',{id:due,requestId},member.tokenValue);const retry=await api('POST',route+'/generateNow',{id:due,requestId},member.tokenValue);assert.equal(one.taskId,retry.taskId);tasks.push(one.taskId);
  const after=await api('GET',route+'/detail?id='+due,undefined,member.tokenValue);assert.equal(after.planDate,dueInfo.planDate);pass('AC-08','manual generation retry deduplicates without advancing cursor');
  const old=await add({taskName:prefix+'默认公历',planCycle:4,planDate:plus(now,20),remindDays:0,deadlineDays:0,rewardPoints:0,punishPoints:0});
  assert.equal((await api('GET',route+'/detail?id='+old,undefined,member.tokenValue)).calendarType,'SOLAR');pass('SOLAR','old creation payload defaults to solar');
 } catch(e){results.push({id:'RUN',status:'FAIL',note:e.message});console.error('FAIL',e.message);process.exitCode=1}
 finally {
  if(member){for(const id of tasks){try{await api('DELETE','/points-service/points/task/basics/delete?id='+id,undefined,member.tokenValue);console.log('CLEAN task',id,'moved to trash')}catch(e){console.error('CLEAN FAIL task',id,e.message);process.exitCode=1}}
   for(const id of plans){try{await api('DELETE',route+'/delete?id='+id,undefined,member.tokenValue);console.log('CLEAN plan',id,'soft deleted')}catch(e){console.error('CLEAN FAIL plan',id,e.message);process.exitCode=1}}}
  fs.writeFileSync('/tmp/iw-lunar-local-api-result.json',JSON.stringify({environment:'local 127.0.0.1:18000 real authenticated APIs',prefix,results,plans,tasks,cleanup:'plans soft-deleted; tasks moved to trash; no bulk trash cleanup'},null,2));
 }
}
main().catch(e=>{console.error(e.message);process.exitCode=1});
