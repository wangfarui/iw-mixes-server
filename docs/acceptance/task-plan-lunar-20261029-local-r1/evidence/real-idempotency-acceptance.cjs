const fs=require('fs'),assert=require('assert/strict');const a=JSON.parse(fs.readFileSync('/Users/wangfarui/workspaces/wfr/iw-mixes_ai/.codex/local/acceptance-accounts.json')).accounts.find(a=>a.key==='member');const base='http://127.0.0.1:18000',route='/points-service/points/task/plan',name='LUNAR-IDEMP-20261029-'+Date.now().toString(36);let token,id,taskid;
async function raw(method,path,data,auth=true){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(auth&&token?{iwtoken:token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(10000)});return r.json()}
async function api(method,path,data){const r=await raw(method,path,data);assert.equal(r.code,200,method+' '+path+' '+(r.message||''));return r.data}
(async()=>{try{
 const login=await api('POST','/auth-service/login/password',{account:a.account,password:a.password});token=login.tokenValue;
 for(const [method,path,body] of [['GET','/calendar?year=2028'],['POST','/convertDate',{calendarType:'SOLAR',planDate:'2026-10-29'}],['POST','/preview',{calendarType:'SOLAR',planCycle:1,planDate:'2026-10-29'}]])assert.equal((await raw(method,route+path,body,false)).code,401);
 console.log('PASS AC-12: three anonymous endpoints reject with 401 even after authenticated calls');
 const p=await api('POST',route+'/preview',{calendarType:'SOLAR',planCycle:1,planDate:'2026-10-29',remindDays:0,deadlineDays:0});const d=new Date(p.serverToday+'T00:00:00Z');d.setUTCDate(d.getUTCDate()+2);const future=d.toISOString().slice(0,10);
 const lunar=await api('POST',route+'/convertDate',{calendarType:'SOLAR',planDate:future});const data={taskName:name,calendarType:'LUNAR',planCycle:4,lunarYear:lunar.lunarYear,lunarMonth:lunar.lunarMonth,lunarDay:lunar.lunarDay,leapMonth:lunar.leapMonth,remindDays:3,deadlineDays:0,rewardPoints:0,punishPoints:0,bookkeepingEnabled:false};
 id=await api('POST',route+'/add',data);let pg=await api('POST','/points-service/points/task/basics/page',{taskName:name,currentPage:1,pageSize:20});let rows=pg.records.filter(x=>x.taskName===name);assert.equal(rows.length,1);taskid=rows[0].id;
 await api('DELETE','/points-service/points/task/basics/delete?id='+taskid);
 await api('PUT',route+'/update',{...data,id,remindDays:4});
 pg=await api('POST','/points-service/points/task/basics/page',{taskName:name,currentPage:1,pageSize:20});rows=pg.records.filter(x=>x.taskName===name);assert.equal(rows.length,1);assert.equal(rows[0].id,taskid);console.log('PASS AC-08: changing schedule after deleting its generated task did not duplicate the automatic period');
 }catch(e){console.error('FAIL',e.message);process.exitCode=1}
 finally{if(id&&token){try{await api('DELETE',route+'/delete?id='+id);console.log('CLEAN plan',id,'soft-deleted; task',taskid,'remains in trash')}catch(e){console.error('CLEAN FAIL',e.message);process.exitCode=1}}}
})()
