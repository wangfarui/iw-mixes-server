#!/usr/bin/env node
/** Loopback-only follow-up. Credentials are supplied by with-local-test-login.py, never persisted. */
import fs from 'node:fs/promises';
import path from 'node:path';
const argv=process.argv.slice(2), arg=k=>argv[argv.indexOf(k)+1];
if(!argv.includes('--execute') || !argv.includes('--dedicated-test-accounts') || arg('--environment')!=='local' || arg('--base-url')!=='http://127.0.0.1:18000') throw Error('Explicit local execution required');
const run=arg('--run-id'), month=arg('--month');
if(!/^[A-Za-z0-9_-]{1,12}$/.test(run)||!/^\d{4}-\d{2}$/.test(month)) throw Error('Invalid run binding');
const dir=path.resolve(arg('--state-dir')); await fs.mkdir(dir,{recursive:true,mode:0o700});
const file=path.join(dir,run+'-extended.json');
let s; try{s=JSON.parse(await fs.readFile(file,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;s={run,month,fixtures:{},completed:[],results:[]};}
if(s.run!==run||s.month!==month)throw Error('Binding changed');
const f=s.fixtures, d='/bookkeeping-service/bookkeeping/dictionaries', r='/bookkeeping-service/bookkeeping/records';
const prefix='X-'+run+'-', first=month+'-01', last=month+'-28';
let active='PREFLIGHT';
const check=(v,msg)=>{if(!v)throw Error(msg);};
async function save(){await fs.writeFile(file+'.tmp',JSON.stringify(s,null,2)+'\n',{mode:0o600});await fs.rename(file+'.tmp',file);}
async function api(role,method,url,body,denied){
 const token=process.env['IW_ACCEPTANCE_TOKEN_'+role];check(!!token,'Missing role token');
 const response=await fetch('http://127.0.0.1:18000'+url,{method,headers:{'Content-Type':'application/json',iwtoken:token},body:body===undefined?undefined:JSON.stringify(body),redirect:'manual',signal:AbortSignal.timeout(20000)});
 const value=await response.json();check(value.code!==401,'Authentication expired');
 if(denied){check(value.code!==200&&denied.test(String(value.message||'')),`Expected denial ${role} ${url.split('?')[0]} code=${value.code} reason=${String(value.message||'').slice(0,100)}`);return value;}
 check(response.ok&&value.code===200,`API ${role} ${method} ${url.split('?')[0]} HTTP=${response.status} code=${value.code} reason=${String(value.message||'').slice(0,100)}`);return value.data;
}
async function step(id,fn){active=id;if(s.completed.includes(id)){console.log(JSON.stringify({scene:id,status:'SKIPPED_PREVIOUS'}));return;}await fn();const v={scene:id,status:'PASS',time:new Date().toISOString()};s.completed.push(id);s.results.push(v);await save();console.log(JSON.stringify(v));}
async function fixture(k,fn){if(!(k in f)){f[k]=await fn();await save();}return f[k];}
async function list(role,type='CATEGORY',scope='PERSONAL'){return api(role,'GET',d+'/items?type='+type+'&scope='+scope);}
async function item(k,role='A',type='CATEGORY',scope='PERSONAL',name=prefix+k){return fixture(k,()=>api(role,'POST',d+'/items',{type,scope,name}));}
const query=(extra={})=>({currentMonth:first,statisticsType:1,isSearchAll:0,queryOnlyMyself:1,...extra});
async function total(role='A',extra={}){return Number((await api(role,'POST','/bookkeeping-service/bookkeeping/consume/totalStatistics',query(extra))).totalAmount);}
async function page(role='A',extra={}){return api(role,'POST',r+'/page',{currentPage:1,pageSize:100,recordStartDate:first,recordEndDate:last,queryOnlyMyself:1,...extra});}
async function bill(k,role,cat,tags,amount=1,shared=0,extra={}){return fixture(k,()=>api(role,'POST',r+'/add',{recordCategory:1,recordDate:first,recordSource:prefix,remark:prefix+k,recordTypeId:cat,recordTags:tags,amount,shared,isStatistics:1,isExcitationRecord:0,...extra}));}
async function detail(k,role='A'){return api(role,'GET',r+'/detail?id='+f[k]);}
async function edit(k,changes={},extra={}){const before=await detail(k);return api('A','PUT',r+'/update',{id:f[k],recordCategory:1,recordDate:first,amount:before.amount,isStatistics:1,remark:prefix+k,shared:before.shared,...extra,...(changes===null?{}:{dictionaryChanges:changes})});}
const denial=/权限|无权|不能|只能|不存在|失效|删除|变化|版本|更新客户端|来源|类型|记账设置/;
try{
 const users={};for(const role of 'ABCDE')users[role]=(await api(role,'GET','/auth-service/user/getUserInfo')).id;
 check(new Set(Object.values(users)).size===5,'Distinct identities required');f.users=users;await save();
 await step('X-MG-WRITES',async()=>{
  await item('family','A','CATEGORY','FAMILY');
  for(const role of 'ABCDE')await item('personal'+role,role);
  const family=await list('A','CATEGORY','FAMILY');
  for(const role of 'CDE'){
   await api(role,'PUT',d+`/items/${f.family.id}/name`,{name:prefix+'denied',revision:f.family.revision},denial);
   await api(role,'PUT',d+`/items/${f.family.id}/status`,{enabled:false,revision:f.family.revision},denial);
   await api(role,'DELETE',d+`/items/${f.family.id}?revision=${f.family.revision}`,undefined,denial);
   await api(role,'PUT',d+'/order',{type:'CATEGORY',scope:'FAMILY',ids:family.items.map(x=>x.id),scopeVersion:family.scopeVersion},denial);
   await api(role,'POST',d+'/copy-personal',{sourceIds:[f['personal'+role].id]},denial);
  }
  for(const role of 'BCDE')await api(role,'PUT',d+`/items/${f.personalA.id}/status`,{enabled:false,revision:f.personalA.revision},denial);
 });
 await step('X-NAMES',async()=>{
  for(const name of ['', '  ', 'x'.repeat(33), '😀'.repeat(33)])await api('A','POST',d+'/items',{type:'CATEGORY',scope:'PERSONAL',name},/名称|字符/);
  await item('unicode','A','CATEGORY','PERSONAL',' '+prefix+'中文😀 ');
  check(f.unicode.name===prefix+'中文😀','Unicode trim');
  const count=32-Array.from(prefix).length;await item('length32','A','CATEGORY','PERSONAL',prefix+'😀'.repeat(count));
  await item('Case','A','CATEGORY');await item('case','A','CATEGORY');
  check(f.Case.id!==f.case.id,'Case sensitive uniqueness');
 });
 await step('X-STATUS-COPY',async()=>{
  await item('copy','A','EXPENSE_TAG');
  const result=await fixture('copied',()=>api('A','POST',d+'/copy-personal',{sourceIds:[f.copy.id]}));
  check(result.created.length===1&&result.created[0].id!==f.copy.id,'Independent family source');
  const again=await api('A','POST',d+'/copy-personal',{sourceIds:[f.copy.id]});check(again.created.length===0&&again.skipped.length===1,'Duplicate copy skipped');
  await api('A','POST',d+'/copy-personal',{sourceIds:[f.personalB.id]},denial);
  await fixture('copyOff',()=>api('A','PUT',d+`/items/${f.copy.id}/status`,{enabled:false,revision:f.copy.revision}));
  await api('A','POST',d+'/items',{type:'EXPENSE_TAG',scope:'PERSONAL',name:f.copy.name},/存在|重复/);
 });
 await step('X-ORDER',async()=>{
  const before=await fixture('orderBefore',()=>list('A'));
  // Reorder only this run's two items while preserving every pre-existing item's relative order.
  const ids=before.items.map(x=>x.id), one=ids.indexOf(f.Case.id), two=ids.indexOf(f.case.id);[ids[one],ids[two]]=[ids[two],ids[one]];
  await fixture('orderWrite',async()=>{await api('A','PUT',d+'/order',{type:'CATEGORY',scope:'PERSONAL',ids,scopeVersion:before.scopeVersion});return true;});
  check(JSON.stringify((await list('A')).items.map(x=>x.id))===JSON.stringify(ids),'Ordering persisted');
  await api('A','PUT',d+'/order',{type:'CATEGORY',scope:'PERSONAL',ids,scopeVersion:before.scopeVersion},denial);
  const current=await list('A');await api('A','PUT',d+'/order',{type:'CATEGORY',scope:'PERSONAL',ids:before.items.map(x=>x.id),scopeVersion:current.scopeVersion});
 });
 await step('X-CONCURRENT-RENAME',async()=>{
  await item('race');
  const responses=await Promise.all(['一','二'].map(async suffix=>{
   try{return {ok:true,value:await api('A','PUT',d+`/items/${f.race.id}/name`,{name:prefix+'race'+suffix,revision:f.race.revision})};}catch(e){check(/API .*code=500/.test(e.message),'Unexpected concurrency error');return {ok:false};}
  }));
  check(responses.filter(x=>x.ok).length===1,'Exactly one rename successor');f.raceNew=responses.find(x=>x.ok).value.item;await save();
 });
 await step('X-SETUP-SNAPSHOTS',async()=>{
  await item('oldCategory');await item('oldTag','A','EXPENSE_TAG');await item('tagTwo','A','EXPENSE_TAG');await item('incomeTag','A','INCOME_TAG');
  await bill('historical','A',f.oldCategory.id,[f.oldTag.id],10,1);
  await bill('private','A',f.personalA.id,[f.oldTag.id],3,0);
  await fixture('renamedCategory',async()=>(await api('A','PUT',d+`/items/${f.oldCategory.id}/name`,{name:prefix+'newCategory',revision:f.oldCategory.revision})).item);
  await fixture('renamedTag',async()=>(await api('A','PUT',d+`/items/${f.oldTag.id}/name`,{name:prefix+'newTag',revision:f.oldTag.revision})).item);
  check((await detail('historical')).recordTypeName===f.oldCategory.name,'Historical category name');
  check((await detail('historical')).recordTagSnapshots[0].name===f.oldTag.name,'Historical tag name');
 });
 await step('X-PRIVACY',async()=>{
  for(const role of 'BC'){
   const options=await api(role,'GET',d+'/options?type=EXPENSE_TAG');check(!options.some(x=>x.id===f.renamedTag.id),'Private option leaked');
   const visible=await api(role,'GET',r+'/detail?id='+f.historical);check(visible.recordTagSnapshots.some(x=>x.name===f.oldTag.name),'Shared snapshot not visible');
   await api(role,'GET',r+'/'+f.private+'/dictionary-sources',undefined,denial);
  }
  for(const role of 'DE'){
   await api(role,'GET',r+'/detail?id='+f.historical,undefined,denial);
   const options=await api(role,'GET',r+`/filter-options?recordStartDate=${first}&recordEndDate=${last}`);
   check(!options.categories.some(x=>x.name===f.oldCategory.name),'Hidden category leaked through filters');
  }
  const sources=await api('A','GET',r+'/'+f.historical+'/dictionary-sources');check(sources.some(x=>x.id===f.oldCategory.id),'Authorized deleted source missing');
 });
 await step('X-FORGED-AND-OLD-CLIENT',async()=>{
  const before=await total();
  for(const [cat,tags] of [[f.personalB.id,[]],[f.renamedCategory.id,[f.incomeTag.id]],[f.oldCategory.id,[]]])await api('A','POST',r+'/add',{recordCategory:1,recordDate:first,recordSource:prefix,amount:.01,recordTypeId:cat,recordTags:tags},denial);
  await api('A','POST',r+'/add',{recordCategory:1,recordDate:first,recordSource:prefix,amount:.01,recordType:1},denial);
  await api('D','POST',r+'/add',{recordCategory:1,recordDate:first,recordSource:prefix,amount:.01,recordTypeId:f.personalD.id,expenseUserId:users.A},denial);
  check(await total()===before,'Rejected writes changed total');
 });
 await step('X-HISTORICAL-FILTER',async()=>{
  const filters=await api('A','GET',r+`/filter-options?recordStartDate=${first}&recordEndDate=${last}`);
  check(filters.categories.some(x=>x.name===f.oldCategory.name),'Historical category absent from filters');
  const rows=await page('A',{categoryNames:[f.oldCategory.name]});check(rows.total===1&&rows.records[0].id===f.historical,'Historical page mismatch');
  check(await total('A',{categoryNames:[f.oldCategory.name]})===10,'Historical sum mismatch');
 });
 await step('X-PARTIAL-UPDATE',async()=>{
  await edit('historical',{categoryId:f.renamedCategory.id});let row=await detail('historical');
  check(row.recordTypeId===f.renamedCategory.id&&row.recordTagSnapshots[0].id===f.oldTag.id,'Category-only update changed tags');
  await edit('historical',{tagIds:[f.oldTag.id,f.tagTwo.id]});row=await detail('historical');
  check(row.recordTagSnapshots.length===2&&row.recordTagSnapshots.some(x=>x.id===f.oldTag.id&&x.name===f.oldTag.name),'Historical tag retention');
  await edit('historical',{tagIds:[f.tagTwo.id]});check((await detail('historical')).recordTagSnapshots.length===1,'Tag removal');
  await edit('historical',{categoryId:null});row=await detail('historical');check(row.recordTypeId==null&&row.recordTagSnapshots.length===1,'Category clear changed tags');
  await edit('historical',{tagIds:[]});check((await detail('historical')).recordTagSnapshots.length===0,'Explicit tags clear');
  check(await total('A',{categoryState:'UNCATEGORIZED'})===10,'Uncategorized sum');
  await api('A','PUT',r+'/update',{id:f.historical,recordCategory:2,amount:10,recordDate:first,dictionaryChanges:{tagIds:[]}},/不支持修改/);
 });
 await step('X-TAG-ALLOCATION',async()=>{
  await bill('multiTag','A',f.renamedCategory.id,[f.renamedTag.id,f.tagTwo.id],100);
  const rows=await api('A','POST','/bookkeeping-service/bookkeeping/consume/tagsStatistics',query());
  for(const name of [f.renamedTag.name,f.tagTwo.name])check(rows.some(x=>x.name===name&&Number(x.amount)===100),'Tag allocation '+name);
  check((await page('A',{tagNames:[f.renamedTag.name,f.tagTwo.name]})).total===1,'OR tag filter duplicates bill');
  check(await total('A',{tagNames:[f.renamedTag.name,f.tagTwo.name]})===100,'OR tag filter duplicates amount');
 });
 await step('X-QUICK-SUCCESSORS',async()=>{
  await item('quickCat');await item('quickTag','A','EXPENSE_TAG');
  await fixture('quick',()=>api('A','POST','/bookkeeping-service/bookkeeping/actions/add',{recordCategory:1,recordSource:prefix+'quick',recordTypeId:f.quickCat.id,recordTags:[f.quickTag.id]}));
  await fixture('quickNext',async()=>(await api('A','PUT',d+`/items/${f.quickCat.id}/name`,{name:prefix+'quickNext',revision:f.quickCat.revision})).item);
  let q=await api('A','GET','/bookkeeping-service/bookkeeping/actions/detail?id='+f.quick);check(q.available&&q.recordTypeId===f.quickNext.id,'Quick rename chain');
  await fixture('quickOff',()=>api('A','PUT',d+`/items/${f.quickTag.id}/status`,{enabled:false,revision:f.quickTag.revision}));
  q=await api('A','GET','/bookkeeping-service/bookkeeping/actions/detail?id='+f.quick);check(q.available===false,'Disabled quick still available');
  await fixture('quickOn',()=>api('A','PUT',d+`/items/${f.quickTag.id}/status`,{enabled:true,revision:f.quickOff.revision}));
  await fixture('quickDeleted',async()=>{await api('A','DELETE',d+`/items/${f.quickNext.id}?revision=${f.quickNext.revision}`);return true;});
  q=await api('A','GET','/bookkeeping-service/bookkeeping/actions/detail?id='+f.quick);check(q.available===false,'Deleted quick silently remapped');
 });
 await step('X-DELETE-HISTORY',async()=>{
  await fixture('sourceDeleted',async()=>{const current=(await list('A')).items.find(x=>x.id===f.personalA.id);check(!!current,'Fixture category disappeared');await api('A','DELETE',d+`/items/${current.id}?revision=${current.revision}`);return true;});
  check((await detail('private')).recordTypeName===f.personalA.name,'Delete changed old bill');
  await edit('private',null);check((await detail('private')).recordTypeName===f.personalA.name,'Ordinary edit changed deleted-source snapshot');
 });
 console.log(JSON.stringify({status:'COMPLETE',detail:'Only executed assertions passed; lifecycle, suppliers, task and budget settlement require separate coverage',stateFile:file}));
}catch(error){const result={scene:active,status:'FAIL',detail:error instanceof Error?error.message:'Runner failure',time:new Date().toISOString()};s.results.push(result);await save();console.error(JSON.stringify(result));process.exitCode=1;}
