#!/usr/bin/env node
import fs from 'node:fs/promises';import path from 'node:path';
const args=process.argv.slice(2),get=k=>args[args.indexOf(k)+1];
if(get('--base-url')!=='http://127.0.0.1:18001'||!args.includes('--execute'))throw Error('Private secondary core required');
const dir=path.resolve(get('--state-dir')),run=get('--run-id');if(!/^[a-zA-Z0-9_-]{1,12}$/.test(run))throw Error('Invalid run');
const file=path.join(dir,run+'-import-voice.json');let s;try{s=JSON.parse(await fs.readFile(file,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;s={fixtures:{},completed:[],results:[]};}const f=s.fixtures;
let active='PRE';const d='/bookkeeping-service/bookkeeping/dictionaries',r='/bookkeeping-service/bookkeeping/records',v='/bookkeeping-service/bookkeeping/assistant/expense';
const check=(v,m)=>{if(!v)throw Error(m);};async function save(){await fs.writeFile(file+'.tmp',JSON.stringify(s,null,2)+'\n',{mode:0o600});await fs.rename(file+'.tmp',file);}
async function api(role,method,url,body,deny){const multipart=body instanceof FormData;const res=await fetch('http://127.0.0.1:18001'+url,{method,headers:{...(multipart?{}:{'Content-Type':'application/json'}),iwtoken:process.env['IW_ACCEPTANCE_TOKEN_'+role]},body:body===undefined?undefined:multipart?body:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const j=await res.json();if(deny){check(j.code!==200&&deny.test(String(j.message||'')),`Expected rejection ${url} code=${j.code}`);return;}check(res.ok&&j.code===200,`API ${url.split('?')[0]} HTTP=${res.status} code=${j.code} reason=${String(j.message||'').slice(0,110)}`);return j.data;}
async function raw(role,method,url,body){const res=await fetch('http://127.0.0.1:18001'+url,{method,headers:{'Content-Type':'application/json',iwtoken:process.env['IW_ACCEPTANCE_TOKEN_'+role]},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const j=await res.json();return {http:res.status,code:j.code,message:String(j.message||'')};}
async function step(id,fn){active=id;if(s.completed.includes(id)){console.log(JSON.stringify({scene:id,status:'SKIPPED_PREVIOUS'}));return;}await fn();const x={scene:id,status:'PASS',time:new Date().toISOString()};s.completed.push(id);s.results.push(x);await save();console.log(JSON.stringify(x));}
async function fixture(k,fn){if(!(k in f)){f[k]=await fn();await save();}return f[k];}
async function balance(){return Number((await api('A','GET','/bookkeeping-service/bookkeeping/wallet/detail')).walletBalance);}
async function upload(file,url){const form=new FormData();form.set('file',new Blob([await fs.readFile('/private/tmp/iw-dict-e2e-20261019/'+file)]),file);return form;}
async function rows(){return api('A','POST',r+'/page',{currentPage:1,pageSize:100,recordStartDate:'2032-08-01',recordEndDate:'2032-08-31',recordSource:'IMPORT-'+run,queryOnlyMyself:1});}
const voicePayload=logId=>({logId,recordCategory:1,recordSource:'VOICE-'+run,recordDate:'2032-09-01',recordTypeId:f.voiceCat.id,recordTags:[],amount:.05,isStatistics:1,isExcitationRecord:0,shared:0});
async function wavDurationMs(){const b=await fs.readFile('/private/tmp/iw-dict-e2e-20261019/expense.wav');const byteRate=b.readUInt32LE(28);let o=12,data=0;while(o+8<=b.length){const id=b.toString('ascii',o,o+4),sz=b.readUInt32LE(o+4);if(id==='data'){data=sz;break;}o+=8+sz+(sz%2);}check(byteRate>0&&data>0,'WAV has no PCM data chunk');return Math.round(data/byteRate*1000);}
async function parse(autoSave=false){const duration=await wavDurationMs();const form=await upload('expense.wav');form.set('format','wav');form.set('sampleRate','16000');form.set('durationMs',String(duration));form.set('autoSave',String(autoSave));console.log(JSON.stringify({scene:'VOICE-ASSET',durationMs:duration}));return api('A','POST',v+'/parseAudio',form);}
try{
 await step('IMPORT-PRIORITY',async()=>{
  await fixture('importCat',()=>api('A','POST',d+'/items',{type:'CATEGORY',scope:'PERSONAL',name:'IMPORT-'+run}));
  await fixture('importFamilyCat',()=>api('E','POST',d+'/items',{type:'CATEGORY',scope:'FAMILY',name:'IMPORT-'+run}));
  const before=await fixture('importBalance',balance);
  await fixture('imported',async()=>{await api('A','POST',r+'/import',await upload('good.xlsx'));return true;});
  const data=await rows();check(data.total===2,'Import row count');
  check(data.records.some(x=>x.recordTypeId===f.importCat.id&&Number(x.amount)===.01),'Personal source did not win import');
  check(data.records.some(x=>x.recordTypeId==null&&x.dictionarySnapshotStatus===1&&Number(x.amount)===.02),'Uncategorized import');
  check(await balance()===before,'Import changed wallet');
 });
 await step('IMPORT-ATOMIC-FAILURE',async()=>{
  const before=(await rows()).total;await api('A','POST',r+'/import',await upload('bad.xlsx'),/导入|不存在|解析|转换|异常/);
  check((await rows()).total===before,'Bad import committed partial rows');
  const options=await api('A','GET',d+'/options?type=CATEGORY');check(!options.some(x=>x.name==='UNKNOWN-'+run),'Unknown import auto-created category');
 });
 await step('VOICE-CONFIRM-REPLAY',async()=>{
  await fixture('voiceCat',()=>api('A','POST',d+'/items',{type:'CATEGORY',scope:'PERSONAL',name:'VOICE-'+run}));
  const parsed=await fixture('parsed',parse);check(parsed.logId>0,'Voice parse did not create owned log');
  console.log(JSON.stringify({scene:'VOICE-PROVIDER',status:parsed.recognizedText?'RECOGNIZED':'NOT_RECOGNIZED',parseStatus:parsed.status,autoSaveEligible:parsed.autoSaveEligible}));
  const before=await fixture('voiceBefore',balance);
  const one=await fixture('confirmed',()=>api('A','POST',v+'/confirm',voicePayload(parsed.logId)));
  const two=await api('A','POST',v+'/confirm',voicePayload(parsed.logId));check(one.recordId===two.recordId&&two.reused===true,'Voice confirm replay duplicated record');
  check(Math.round((before-await balance())*100)===5,'Voice wallet must change once');
  const denied=await raw('B','POST',v+'/confirm',voicePayload(parsed.logId));check(denied.code!==200,'Cross-user voice confirm was not rejected');
  const bRows=await api('B','POST',r+'/page',{currentPage:1,pageSize:100,recordStartDate:'2032-09-01',recordEndDate:'2032-09-30',recordSource:'VOICE-'+run,queryOnlyMyself:1});check(bRows.total===0,'Cross-user voice confirm created a record');
  console.log(JSON.stringify({scene:'VOICE-CROSS-USER',result:'REJECTED',code:denied.code,bRows:bRows.total}));
 });
 await step('VOICE-STALE-SOURCE',async()=>{
  const parsed=await fixture('parsedStale',parse);check(parsed.logId>0,'Second owned log');
  await fixture('voiceOff',()=>api('A','PUT',d+`/items/${f.voiceCat.id}/status`,{enabled:false,revision:f.voiceCat.revision}));
  const before=await balance();const stale=await raw('A','POST',v+'/confirm',voicePayload(parsed.logId));check(stale.code!==200,'Stale voice draft was accepted');check(await balance()===before,'Stale voice draft changed wallet');console.log(JSON.stringify({scene:'VOICE-STALE-OBSERVED',code:stale.code}));
 });
 await step('VOICE-AUTO-SAVE',async()=>{
  const parsed=await fixture('parsedAuto',()=>parse(true));
  check(parsed.logId>0,'Automatic parse log');
  if(parsed.autoSaved){check(parsed.recordId>0&&parsed.autoSaveEligible,'Auto save result contract');const again=await api('A','POST',v+'/confirm',{...voicePayload(parsed.logId),recordTypeId:f.importCat.id});check(again.recordId===parsed.recordId&&again.reused,'Auto-saved confirmation replay');}
  else check(!parsed.autoSaveEligible||parsed.status==='NEED_CONFIRM','Eligible auto-save unexpectedly skipped');
  console.log(JSON.stringify({scene:'VOICE-AUTO-OBSERVED',autoSaved:parsed.autoSaved,autoSaveEligible:parsed.autoSaveEligible,status:parsed.status}));
 });
 console.log(JSON.stringify({status:'COMPLETE',scope:'private clone import and voice; inspect supplier observation separately'}));
}catch(e){const x={scene:active,status:'FAIL',detail:e.message,time:new Date().toISOString()};s.results.push(x);await save();console.error(JSON.stringify(x));process.exitCode=1;}
