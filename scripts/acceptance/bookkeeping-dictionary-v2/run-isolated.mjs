#!/usr/bin/env node
/** Destructive lifecycle checks: private database + private Redis + secondary local core ONLY. */
import fs from 'node:fs/promises';import path from 'node:path';
const args=process.argv.slice(2),get=k=>args[args.indexOf(k)+1];
if(get('--base-url')!=='http://127.0.0.1:18001'||!args.includes('--execute')||!args.includes('--dedicated-test-accounts'))throw Error('Private secondary core required');
const run=get('--run-id');if(!/^[a-zA-Z0-9_-]{1,12}$/.test(run))throw Error('Invalid run');
const dir=path.resolve(get('--state-dir'));await fs.mkdir(dir,{recursive:true,mode:0o700});const file=path.join(dir,run+'-isolated.json');
let s;try{s=JSON.parse(await fs.readFile(file,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;s={run,fixtures:{},completed:[],results:[]};}const f=s.fixtures;
let active='PRE';const prefix='ISO-'+run+'-',d='/bookkeeping-service/bookkeeping/dictionaries',r='/bookkeeping-service/bookkeeping/records',g='/auth-service/family/group';
const check=(v,m)=>{if(!v)throw Error(m);};async function save(){await fs.writeFile(file+'.tmp',JSON.stringify(s,null,2)+'\n',{mode:0o600});await fs.rename(file+'.tmp',file);}
async function api(role,method,url,body,deny){const res=await fetch('http://127.0.0.1:18001'+url,{method,headers:{'Content-Type':'application/json',iwtoken:process.env['IW_ACCEPTANCE_TOKEN_'+role]},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});const j=await res.json();if(deny){check(j.code!==200&&deny.test(String(j.message||'')),`Expected denial ${role} ${url} code=${j.code} reason=${String(j.message||'').slice(0,90)}`);return j;}check(res.ok&&j.code===200,`API ${role} ${method} ${url.split('?')[0]} code=${j.code} reason=${String(j.message||'').slice(0,90)}`);return j.data;}
async function step(id,fn){active=id;if(s.completed.includes(id)){console.log(JSON.stringify({scene:id,status:'SKIPPED_PREVIOUS'}));return;}await fn();const v={scene:id,status:'PASS',time:new Date().toISOString()};s.completed.push(id);s.results.push(v);await save();console.log(JSON.stringify(v));}
async function fixture(k,fn){if(!(k in f)){f[k]=await fn();await save();}return f[k];}
async function item(k,role='A',type='CATEGORY',scope='PERSONAL'){return fixture(k,()=>api(role,'POST',d+'/items',{name:prefix+k,type,scope}));}
async function management(role,type='CATEGORY',scope='PERSONAL'){return api(role,'GET',d+`/items?type=${type}&scope=${scope}`);}
const deny=/权限|无权|不能|只能|不存在|失效|删除|变化|版本|更新客户端|来源|类型|记账设置|家人/;
const today=new Date().toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'});
async function addBill(k,role,cat,amount,extra={}){return fixture(k,()=>api(role,'POST',r+'/add',{recordDate:today,recordCategory:1,recordSource:prefix,recordTypeId:cat,amount,recordTags:[],shared:0,isStatistics:1,isExcitationRecord:0,remark:prefix+k,...extra}));}
const template=(cat,tag)=>({recordCategory:1,recordSource:prefix+'task',amount:.03,recordTypeId:cat,recordTags:tag?[tag]:[],shared:0,isStatistics:1,isExcitationRecord:0});
try{
 await step('ISO-PREFLIGHT',async()=>{
  const u={};for(const role of 'ABCDE')u[role]=(await api(role,'GET','/auth-service/user/getUserInfo')).id;check(new Set(Object.values(u)).size===5,'Distinct private identities');f.users=u;
  const group=await api('A','GET',g+'/myGroup');f.groupG=group.id;check(group.currentUserRole===1,'Owner precondition');await save();
 });
 await step('ISO-BUDGET-PAYER',async()=>{
  await item('budgetA');await item('budgetB','B');
  await fixture('budgetAId',()=>api('A','POST','/bookkeeping-service/bookkeeping/budget/add',{budgetType:11,recordTypeId:f.budgetA.id,budgetAmount:9999,rewardPoints:0,punishPoints:0}));
  await fixture('budgetBId',()=>api('B','POST','/bookkeeping-service/bookkeeping/budget/add',{budgetType:11,recordTypeId:f.budgetB.id,budgetAmount:9999,rewardPoints:0,punishPoints:0}));
  await addBill('ownBudget','A',f.budgetA.id,1.11);
  await addBill('delegatedBudget','A',f.budgetB.id,2.22,{expenseUserId:f.users.B,shared:1}).catch(async error=>{
   // Recorder cannot select another user's private source; delegate using the recorder's own trusted category.
   if(!/选项已失效/.test(error.message))throw error;
   const same=(await api('A','PUT',d+`/items/${f.budgetA.id}/name`,{name:f.budgetB.name,revision:f.budgetA.revision})).item;f.delegateCat=same;await save();
   await addBill('delegatedBudget','A',same.id,2.22,{expenseUserId:f.users.B,shared:1});
  });
  const rowsA=await api('A','GET','/bookkeeping-service/bookkeeping/budget/categoryBudget?budgetType=11');const rowsB=await api('B','GET','/bookkeeping-service/bookkeeping/budget/categoryBudget?budgetType=11');
  check(rowsA.some(x=>x.id===f.budgetAId&&Number(x.usedAmount)===1.11),'Recorder personal budget');
  check(rowsB.some(x=>x.id===f.budgetBId&&Number(x.usedAmount)===2.22),'Actual payer budget');
 });
 await step('ISO-TASK-IDEMPOTENCY',async()=>{
  await item('taskCat');await item('taskTag','A','EXPENSE_TAG');
  await fixture('plan',()=>api('A','POST','/points-service/points/task/plan/add',{taskName:prefix+'plan',planCycle:1,planDate:'2035-01-01',rewardPoints:0,punishPoints:0,bookkeepingEnabled:true,bookkeepingTemplate:template(f.taskCat.id,f.taskTag.id)}));
  const generated=await fixture('generated',()=>api('A','POST','/points-service/points/task/plan/generateNow',{id:f.plan,requestId:'ISO_REQUEST_'+run+'_01'}));
  check(generated.taskId>0,'Generated task ID');
  const repeat=await api('A','POST','/points-service/points/task/plan/generateNow',{id:f.plan,requestId:'ISO_REQUEST_'+run+'_01'});check(repeat.taskId===generated.taskId,'Generation request idempotency');
  await api('A','PUT','/points-service/points/task/plan/updateStatus',{id:f.plan,status:0});
  const done=await fixture('done',()=>api('A','PUT','/points-service/points/task/basics/updateStatus',{id:generated.taskId,taskStatus:1}));check(done.bookkeepingRecordId>0,'Completion created bill');
  const again=await api('A','PUT','/points-service/points/task/basics/updateStatus',{id:generated.taskId,taskStatus:1});check(again.bookkeepingRecordId===done.bookkeepingRecordId,'Completion duplicated bill');
  await fixture('taskCatNext',async()=>(await api('A','PUT',d+`/items/${f.taskCat.id}/name`,{name:prefix+'taskCatNext',revision:f.taskCat.revision})).item);
  const afterRename=await api('A','PUT','/points-service/points/task/basics/updateStatus',{id:generated.taskId,taskStatus:1});check(afterRename.bookkeepingRecordId===done.bookkeepingRecordId,'Rename rebuilt existing bill');
  const bill=await api('A','GET',r+'/detail?id='+done.bookkeepingRecordId);check(bill.recordTypeName===f.taskCat.name,'Existing task bill renamed');
 });
 await step('ISO-TASK-REBUILD',async()=>{
  await api('A','PUT','/points-service/points/task/basics/updateStatus',{id:f.generated.taskId,taskStatus:0});
  await fixture('taskBillDeleted',async()=>{await api('A','DELETE',r+'/delete?id='+f.done.bookkeepingRecordId);return true;});
  const done=await fixture('rebuilt',()=>api('A','PUT','/points-service/points/task/basics/updateStatus',{id:f.generated.taskId,taskStatus:1}));check(done.bookkeepingRecordId>0&&done.bookkeepingRecordId!==f.done.bookkeepingRecordId,'Deleted task bill not rebuilt');
  check((await api('A','GET',r+'/detail?id='+done.bookkeepingRecordId)).recordTypeName===f.taskCatNext.name,'Rebuild did not follow successor');
 });
 await step('ISO-ROLE-DOWNGRADE',async()=>{
  await item('family','A','CATEGORY','FAMILY');
  await api('A','POST',g+'/assignRole',{groupId:f.groupG,userId:f.users.B,role:3});
  try{await api('B','PUT',d+`/items/${f.family.id}/name`,{name:prefix+'denied',revision:f.family.revision},deny);}finally{await api('A','POST',g+'/assignRole',{groupId:f.groupG,userId:f.users.B,role:2});}
 });
 await step('ISO-FAMILY-INITIALIZE',async()=>{
  const before=await fixture('personalEBefore',async()=>(await management('E')).items.map(x=>({id:x.id,name:x.name})));
  await fixture('groupH',()=>api('E','POST',g+'/add',{groupName:prefix+'H',maxMember:10}));
  const group=await api('E','GET',g+'/myGroup');check(group.id>0&&group.id!==f.groupG,'New family H');f.groupH=group.id;await save();
  check(JSON.stringify((await management('E')).items.map(x=>({id:x.id,name:x.name})))===JSON.stringify(before),'Creating family changed personal values');
  const original=await fixture('defaultsH',async()=>(await management('E','CATEGORY','FAMILY')).items);
  check(original.length===6,'New family default categories');check((await management('E','EXPENSE_TAG','FAMILY')).items.length===0,'New family tags not empty');
  for(const row of original){const current=(await management('E','CATEGORY','FAMILY')).items.find(x=>x.id===row.id);if(current)await api('E','DELETE',d+`/items/${current.id}?revision=${current.revision}`);}
  await api('E','GET',g+'/myGroup');check((await management('E','CATEGORY','FAMILY')).items.length===0,'Empty family defaults reinitialized');
 });
 await step('ISO-LEAVE-JOIN',async()=>{
  await addBill('familyBillB','B',f.family.id,.04,{shared:1});
  await api('B','POST',g+'/quit?groupId='+f.groupG);
  await fixture('familyNext',async()=>(await api('A','PUT',d+`/items/${f.family.id}/name`,{name:prefix+'familyNext',revision:f.family.revision})).item);
  check((await api('B','GET',r+'/detail?id='+f.familyBillB)).recordTypeName===f.family.name,'Departure changed own bill snapshot');
  check(!(await api('B','GET',d+'/options?type=CATEGORY')).some(x=>x.id===f.familyNext.id),'Old family option remained available');
  await api('A','GET',r+'/detail?id='+f.familyBillB,undefined,deny);
  const invitation=await api('E','POST',g+'/generateInvite',{groupId:f.groupH,validHours:1});
  await api('B','POST',g+'/join',{inviteCode:invitation.inviteCode});
  check((await api('B','GET',g+'/myGroup')).id===f.groupH,'Join H failed');
  await api('B','POST',r+'/add',{recordCategory:1,recordDate:today,recordSource:prefix,amount:.01,recordTypeId:f.familyNext.id},deny);
 });
 await step('ISO-TRANSFER-DISSOLVE',async()=>{
  await api('A','POST',g+'/transferOwner',{groupId:f.groupG,newOwnerUserId:f.users.C});
  check((await management('C','CATEGORY','FAMILY')).items.some(x=>x.id===f.familyNext.id),'Transfer moved dictionary ownership');
  await api('C','DELETE',g+'/delete?id='+f.groupG);
  check(await api('A','GET',g+'/myGroup')==null,'Dissolution left membership');
  check(!(await api('A','GET',d+'/options?type=CATEGORY')).some(x=>x.id===f.familyNext.id),'Dissolved source remained selectable');
  const invitation=await api('E','POST',g+'/generateInvite',{groupId:f.groupH,validHours:1});await api('A','POST',g+'/join',{inviteCode:invitation.inviteCode});
  check(!(await api('A','GET',d+'/options?type=CATEGORY')).some(x=>x.id===f.familyNext.id),'Old G source became an H source');
 });
 console.log(JSON.stringify({status:'COMPLETE',environment:'private-database-and-private-redis',stateFile:file}));
}catch(error){const v={scene:active,status:'FAIL',detail:error.message,time:new Date().toISOString()};s.results.push(v);await save();console.error(JSON.stringify(v));process.exitCode=1;}
