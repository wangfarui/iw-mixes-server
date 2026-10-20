#!/usr/bin/env node
/** Explicitly invoked, isolated API acceptance. Never imported by build scripts. */
import { readFile, writeFile, mkdir, open, unlink, rename } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const usage = `Node >=20. No requests run without every required flag:
node scripts/acceptance/bookkeeping-dictionary-v2/run.mjs --execute --base-url http://localhost:18000 --environment local --run-id <1-12 ASCII chars> --month <YYYY-MM> --dedicated-test-accounts
For environment test also require --allow-host <exact hostname>.
Optional --state-dir <directory>; tokens only IW_ACCEPTANCE_TOKEN_A/B/C/D/E environment variables.
All five identities are required: A owner, B parent, C member, D child in G; E has no family.
First run requires a completely empty selected month for A/B/C personal/family scopes.
No cleanup is performed. Existing completed steps print SKIPPED_PREVIOUS on resume.`;
const roles = ['A', 'B', 'C', 'D', 'E'];
class Failure extends Error { constructor(message, status='FAIL') { super(message); this.status=status; } }
function demand(condition, message, status) { if (!condition) throw new Failure(message, status); }
const args = new Map();
for (let i=2; i<process.argv.length; i++) {
  const flag=process.argv[i];
  demand(/^--[a-z-]+$/.test(flag), 'Invalid argument', 'BLOCKED');
  demand(!args.has(flag), 'Duplicate argument', 'BLOCKED');
  if (['--execute','--dedicated-test-accounts','--help'].includes(flag)) args.set(flag,true);
  else { demand(process.argv[i+1] && !process.argv[i+1].startsWith('--'), 'Missing argument value', 'BLOCKED'); args.set(flag,process.argv[++i]); }
}
if (args.has('--help')) { console.log(usage); process.exit(0); }
const allowed = ['--execute','--base-url','--environment','--run-id','--month','--dedicated-test-accounts','--allow-host','--state-dir'];
let lockPath;
let state;
let statePath;
let active='PREFLIGHT';
async function main() {
  demand([...args.keys()].every(k=>allowed.includes(k)), 'Unknown argument', 'BLOCKED');
  demand(args.has('--execute') && args.has('--dedicated-test-accounts'), 'Explicit execution and dedicated-account declaration required', 'BLOCKED');
  const env=args.get('--environment'), runId=args.get('--run-id'), month=args.get('--month');
  demand(['local','test'].includes(env), 'Environment must be local or test', 'BLOCKED');
  demand(/^[A-Za-z0-9_-]{1,12}$/.test(runId||''), 'Invalid run ID', 'BLOCKED');
  demand(/^\d{4}-(0[1-9]|1[0-2])$/.test(month||''), 'Invalid month', 'BLOCKED');
  let base; try { base=new URL(args.get('--base-url')); } catch { throw new Failure('Explicit valid base URL required','BLOCKED'); }
  demand(['http:','https:'].includes(base.protocol) && !base.username && !base.password && !base.search && !base.hash && base.pathname==='/', 'Base URL must be an HTTP origin without credentials or path','BLOCKED');
  const host=base.hostname.toLowerCase();
  demand(!/(^|[.-])prod(uction)?([.-]|$)/.test(host) && host!=='api.itwray.com' && !host.endsWith('.itwray.com'), 'Production host refused','BLOCKED');
  const local=['localhost','127.0.0.1','[::1]'].includes(host);
  demand(env==='local'?local:args.get('--allow-host')===host, 'Host must match local policy or explicit test allow-host','BLOCKED');
  for (const role of roles) demand(typeof process.env[`IW_ACCEPTANCE_TOKEN_${role}`]==='string' && process.env[`IW_ACCEPTANCE_TOKEN_${role}`].length>0, `Missing dedicated token for role ${role}`,'BLOCKED');
  const stateDir=path.resolve(args.get('--state-dir') || path.join(path.dirname(fileURLToPath(import.meta.url)),'.runs'));
  await mkdir(stateDir,{recursive:true,mode:0o700}); statePath=path.join(stateDir,`${runId}.json`); lockPath=path.join(stateDir,`${runId}.lock`);
  try { const lock=await open(lockPath,'wx',0o600); await lock.close(); }
  catch { lockPath=undefined; throw new Failure('Run already locked; inspect its process before manually removing stale lock','BLOCKED'); }
  const binding={baseUrl:base.origin,environment:env,runId,month};
  try { state=JSON.parse(await readFile(statePath,'utf8')); }
  catch(error) { if (error.code!=='ENOENT') throw new Failure('State is unreadable; do not reset it blindly','BLOCKED'); state={binding,fixtures:{},completed:{},results:[]}; }
  demand(JSON.stringify(state.binding)===JSON.stringify(binding), 'State/environment binding mismatch','BLOCKED');
  const f=state.fixtures;
  const dict='/bookkeeping-service/bookkeeping/dictionaries';
  const records='/bookkeeping-service/bookkeeping/records';
  const name=`验收餐饮-${runId}`, renamed=`验收用餐-${runId}`, tag=`验收外卖-${runId}`;
  const source=`ACCEPT-${runId}`;
  const first=`${month}-01`, last=`${month}-${new Date(Number(month.slice(0,4)),Number(month.slice(5,7)),0).getDate()}`;
  async function save() { await writeFile(`${statePath}.tmp`,`${JSON.stringify(state,null,2)}\n`,{mode:0o600}); await rename(`${statePath}.tmp`,statePath); }
  async function api(role,method,url,body,deniedPattern) {
    let response,json;
    try {
      response=await fetch(new URL(url,base),{method,headers:{'Content-Type':'application/json',iwtoken:process.env[`IW_ACCEPTANCE_TOKEN_${role}`]},body:body===undefined?undefined:JSON.stringify(body),redirect:'manual',signal:AbortSignal.timeout(20000)});
      json=await response.json();
    } catch { throw new Failure(`Request unavailable or invalid JSON (${role} ${method} ${url.split('?')[0]})`,'BLOCKED'); }
    demand(![301,302,303,307,308,401].includes(response.status) && json.code!==401, `Authentication/redirect failure for ${role}`,'BLOCKED');
    if (deniedPattern) {
      demand(json.code!==200 && deniedPattern.test(String(json.message||'')), `Expected explicit business denial (${role} ${method} ${url.split('?')[0]})`);
      return;
    }
    demand(response.ok && json.code===200, `API failed (${role} ${method} ${url.split('?')[0]}, HTTP ${response.status}, code ${Number(json.code)||'unknown'})`);
    return json.data;
  }
  async function step(id,label,action) {
    active=id;
    if(state.completed[id]) { console.log(JSON.stringify({scene:id,status:'SKIPPED_PREVIOUS',detail:'Previously completed fixture stage; not revalidated this invocation'})); return; }
    await action();
    const result={scene:id,status:'PASS',detail:label,time:new Date().toISOString()};
    state.results.push(result); state.completed[id]=result.time; await save(); console.log(JSON.stringify(result));
  }
  async function list(role,type='CATEGORY',scope='PERSONAL') { return api(role,'GET',`${dict}/items?type=${type}&scope=${scope}`); }
  async function ensureItem(key,role,type,scope,itemName) {
    if(f[key]) return f[key];
    const existing=(await list(role,type,scope)).items.filter(item=>item.name===itemName);
    demand(existing.length<=1,'Duplicate fixture dictionary item');
    f[key]=existing[0] || await api(role,'POST',`${dict}/items`,{type,scope,name:itemName});
    await save(); return f[key];
  }
  async function total(role,personal,categoryNames) {
    const data=await api(role,'POST','/bookkeeping-service/bookkeeping/consume/totalStatistics',{currentMonth:first,statisticsType:1,isSearchAll:0,queryOnlyMyself:personal?1:0,...(categoryNames?{categoryNames}:{})});
    demand(data && Number.isFinite(Number(data.totalAmount)),'Invalid total statistics payload');
    return Number(data.totalAmount);
  }
  async function ensureBill(key,role,amount,shared,categoryId,payer,tagIds=[]) {
    if(f[key]) return f[key];
    const payload={recordDate:first,recordCategory:1,recordSource:source,amount,shared,isStatistics:1,isExcitationRecord:0,recordTypeId:categoryId,recordTags:tagIds,remark:`${source}:${key}`,...(payer?{expenseUserId:payer}:{})};
    // Recover an ambiguous network outcome by source + immutable fixture marker before adding.
    const page=await api(role,'POST',`${records}/page`,{currentPage:1,pageSize:100,recordStartDate:first,recordEndDate:last,recordSource:source,queryOnlyMyself:0});
    demand(Number(page.total)<=100,'Too many fixture rows; inspect manually','BLOCKED');
    const recovered=page.records.filter(row=>row.remark===payload.remark);
    demand(recovered.length<=1,'Duplicate bill fixture detected');
    if(recovered.length) {
      const row=recovered[0]; demand(Number(row.amount)===amount && row.recordTypeId===categoryId,'Recovered fixture changed; inspect before resuming','BLOCKED');
      f[key]=row.id;
    } else f[key]=await api(role,'POST',`${records}/add`,payload);
    demand(Number.isInteger(f[key])&&f[key]>0,'Missing created bill ID'); await save(); return f[key];
  }
  // Recheck identities on EVERY invocation. Never persist full user info, which contains tokens.
  const users={};
  for(const role of roles) users[role]=(await api(role,'GET','/auth-service/user/getUserInfo')).id;
  demand(new Set(Object.values(users)).size===5 && Object.values(users).every(Number.isInteger),'Five distinct dedicated identities required','BLOCKED');
  if(f.users) demand(JSON.stringify(f.users)===JSON.stringify(users),'Role identity changed on resume','BLOCKED'); else { f.users=users; await save(); }
  const groups={}; for(const role of roles) groups[role]=await api(role,'GET','/auth-service/family/group/myGroup');
  const roleCode=value=>typeof value==='object'?value?.code:value;
  demand(groups.A?.id>0 && ['B','C','D'].every(r=>groups[r]?.id===groups.A.id) && groups.E==null,'A/B/C/D must share G and E must have no family','BLOCKED');
  for(const [role,code] of [['A',1],['B',2],['C',3],['D',4]]) demand(roleCode(groups[role].currentUserRole)===code,`Role ${role} does not match required family role`,'BLOCKED');
  if(f.groupId) demand(f.groupId===groups.A.id,'Family changed on resume','BLOCKED'); else { f.groupId=groups.A.id; await save(); }
  await step('PRE-EMPTY','Empty baseline month checked',async()=>{
    for(const role of ['A','B','C']) for(const personal of [true,false]) demand(await total(role,personal)===0,`Dedicated month must have no existing visible expenses (${role}/${personal?'personal':'family'})`,'BLOCKED');
  });
  await step('MG-01','Owner/parent allowed; member/child/no-family denied',async()=>{
    for(const role of roles) {
      const m=await list(role,'CATEGORY','PERSONAL'); demand(m.canManage===true,`Personal management ${role}`);
    }
    for(const role of ['A','B','C','D']) { const m=await list(role,'CATEGORY','FAMILY'); demand(m.canManage===['A','B'].includes(role),`Family management ${role}`); }
    for(const role of ['C','D','E']) await api(role,'POST',`${dict}/items`,{type:'CATEGORY',scope:'FAMILY',name:`拒绝-${runId}`},/权限|无权/);
    await ensureItem('categoryA','A','CATEGORY','PERSONAL',name);
    await ensureItem('categoryB','B','CATEGORY','PERSONAL',name);
    await ensureItem('categoryG','A','CATEGORY','FAMILY',name);
    await ensureItem('parentWrite','B','EXPENSE_TAG','FAMILY',`家长-${runId}`);
    await ensureItem('tagA','A','EXPENSE_TAG','PERSONAL',tag);
  });
  await step('MG-02','Same-scope duplicate denied, cross-scope duplicate allowed',async()=>{
    await api('A','POST',`${dict}/items`,{type:'CATEGORY',scope:'PERSONAL',name},/重复|存在/);
    demand(f.categoryA.id!==f.categoryG.id,'Cross-scope IDs must differ');
  });
  await step('OP-01','Merged current options prefer own personal source',async()=>{
    for(const role of ['A','B']) {
      const options=await api(role,'GET',`${dict}/options?type=CATEGORY`);
      const matches=options.filter(item=>item.name===name);
      demand(matches.length===1 && matches[0].id===f[`category${role}`].id,`Personal priority ${role}`);
    }
    const options=await api('C','GET',`${dict}/options?type=EXPENSE_TAG`);
    demand(!options.some(item=>item.id===f.tagA.id),'Another member private tag leaked');
  });
  await step('MG-07','Old generic write endpoints reject migrated types',async()=>{
    await api('A','POST','/auth-service/dict/add',{dictType:4002,dictName:`旧入口-${runId}`,dictCode:123,dictStatus:1,sort:1,isSyncAll:1},/记账设置|更新客户端/);
    await api('A','PUT','/auth-service/dict/update',{id:f.categoryA.id,dictType:4004,dictName:`绕过-${runId}`,dictStatus:1,sort:1},/记账设置|更新客户端/);
    await api('A','DELETE',`/auth-service/dict/delete?id=${f.categoryA.id}`,undefined,/记账设置|更新客户端/);
  });
  await step('FIX-R1-R5','Five payment-owner fixtures created/recovered',async()=>{
    await ensureBill('R1','A',100,0,f.categoryA.id,users.A,[f.tagA.id]);
    await ensureBill('R2','A',200,1,f.categoryA.id,users.A);
    await ensureBill('R3','A',80,1,f.categoryA.id,users.B);
    await ensureBill('R4','B',300,1,f.categoryB.id,users.B);
    await ensureBill('R5','B',50,0,f.categoryB.id,users.B);
  });
  await step('ST-01','Personal/family totals preserve payment-owner visibility',async()=>{
    for(const [role,personal,expected] of [['A',true,300],['B',true,430],['A',false,680],['B',false,630],['C',false,580]]) {
      const actual=await total(role,personal); demand(actual===expected,`Amount mismatch ${role}/${personal?'personal':'family'} expected=${expected} actual=${actual}`);
    }
    for(const [role,expected] of [['A',680],['B',630],['C',580]]) {
      const rows=await api(role,'POST','/bookkeeping-service/bookkeeping/consume/pieChartStatistics',{currentMonth:first,statisticsType:1,isSearchAll:0,queryOnlyMyself:0,isQueryLastMonth:false});
      const categories=rows.filter(row=>row.recordTypeName===name);
      demand(categories.length===1 && Number(categories[0].amount)===expected,`Same-name source aggregation ${role}`);
    }
  });
  await step('MG-03','Rename creates one successor; old revision rejected',async()=>{
    // A previous response may have been lost: recover only this run's exact successor name.
    const items=(await list('A')).items; let next=items.find(item=>item.name===renamed);
    if(!next) {
      const result=await api('A','PUT',`${dict}/items/${f.categoryA.id}/name`,{name:renamed,revision:f.categoryA.revision});
      demand(result.oldId===f.categoryA.id,'Rename old ID contract'); next=result.item;
    }
    demand(next.id!==f.categoryA.id,'Rename must allocate a new source'); f.renamedCategory=next; await save();
    await api('A','PUT',`${dict}/items/${f.categoryA.id}/name`,{name:renamed,revision:f.categoryA.revision},/版本|变化/);
  });
  await step('HS-01','Existing category and tag snapshots survive source rename',async()=>{
    for(const key of ['R1','R2','R3']) { const row=await api('A','GET',`${records}/detail?id=${f[key]}`); demand(row.recordTypeId===f.categoryA.id && row.recordTypeName===name,`Historical category ${key}`); }
    let next=(await list('A','EXPENSE_TAG')).items.find(item=>item.name===`验收新标签-${runId}`);
    if(!next) next=(await api('A','PUT',`${dict}/items/${f.tagA.id}/name`,{name:`验收新标签-${runId}`,revision:f.tagA.revision})).item;
    f.renamedTag=next; await save();
    const row=await api('A','GET',`${records}/detail?id=${f.R1}`);
    demand(row.recordTagSnapshots?.some(item=>item.id===f.tagA.id && item.name===tag),'Historical tag changed');
  });
  await step('HS-02','Non-dictionary edit preserves invalid-source snapshots',async()=>{
    await api('A','PUT',`${records}/update`,{id:f.R1,recordCategory:1,amount:100,recordDate:first,remark:`${source}:R1`,isStatistics:1});
    const row=await api('A','GET',`${records}/detail?id=${f.R1}`);
    demand(row.recordTypeId===f.categoryA.id && row.recordTypeName===name,'Category changed on ordinary edit');
    demand(row.recordTagSnapshots?.some(item=>item.id===f.tagA.id && item.name===tag),'Tag changed on ordinary edit');
  });
  await step('HS-02-SHARING','Omitted sharing flag preserves the existing private fixture',async()=>{
    const before=await api('A','GET',`${records}/detail?id=${f.R1}`);
    demand(before.recordSource===source && Number(before.amount)===100,'Refuse to repair a non-fixture record','BLOCKED');
    // Restore only this run's R1 after the historical failing edit, then repeat the exact failing request.
    if(before.shared!==0) await api('A','PUT',`${records}/update`,{id:f.R1,recordCategory:1,amount:100,recordDate:first,remark:`${source}:R1`,isStatistics:1,shared:0});
    await api('A','PUT',`${records}/update`,{id:f.R1,recordCategory:1,amount:100,recordDate:first,remark:`${source}:R1`,isStatistics:1});
    const after=await api('A','GET',`${records}/detail?id=${f.R1}`);
    demand(after.shared===0,'Ordinary edit unexpectedly made the private fixture shared');
  });
  await step('ST-02','New name stays separate from historical category',async()=>{
    await ensureBill('R6','A',20,1,f.renamedCategory.id,users.A);
    for(const [role,personal,expected] of [['A',true,320],['A',false,700],['B',true,430],['B',false,650]]) {
      const actual=await total(role,personal);
      demand(actual===expected,`Post-rename total ${role}/${personal} expected=${expected} actual=${actual}`);
    }
    const rows=await api('A','POST','/bookkeeping-service/bookkeeping/consume/pieChartStatistics',{currentMonth:first,statisticsType:1,isSearchAll:0,queryOnlyMyself:0,isQueryLastMonth:false});
    demand(rows.filter(r=>r.recordTypeName===name).length===1 && Number(rows.find(r=>r.recordTypeName===name).amount)===680,'Old category amount moved');
    demand(rows.filter(r=>r.recordTypeName===renamed).length===1 && Number(rows.find(r=>r.recordTypeName===renamed).amount)===20,'New category amount incorrect');
  });
  const manual=['UI-01..UI-06','MG-05','MG-06','HS-03..HS-06','FG-01..FG-07','ST-03..ST-08','BU-01..BU-04','EN-01..EN-12','WB-01','DB-01..DB-06'];
  console.log(JSON.stringify({status:'NOT_EXECUTED',method:'MANUAL_OR_SEPARATE_TOOL',scenes:manual,detail:'Includes task idempotency, lifecycle/concurrency, imports/voice, SQL and all UI. Listed core stages cover only their explicit assertions.'}));
  console.log(JSON.stringify({status:'COMPLETE',stateFile:statePath,detail:'No cleanup performed; PASS applies only to stages executed by this invocation.'}));
}
try { await main(); }
catch(error) {
  // Never print remote response, request body, headers, environment or arbitrary exception text.
  const result={scene:active,status:error instanceof Failure?error.status:'BLOCKED',detail:error instanceof Failure?error.message:'Local runner failure; inspect permissions/state without sharing credentials',time:new Date().toISOString()};
  console.error(JSON.stringify(result)); process.exitCode=1;
  if(state && statePath) { state.results.push(result); try { await writeFile(`${statePath}.tmp`,`${JSON.stringify(state,null,2)}\n`,{mode:0o600}); await rename(`${statePath}.tmp`,statePath); } catch { /* Preserve original failure. */ } }
} finally { if(lockPath) await unlink(lockPath).catch(()=>{}); }
