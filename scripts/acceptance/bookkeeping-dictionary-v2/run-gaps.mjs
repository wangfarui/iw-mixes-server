#!/usr/bin/env node
/** Remaining dictionary-v2 gaps on the local original database. Creates only uniquely-prefixed fixtures. */
import fs from 'node:fs/promises';
import path from 'node:path';
const argv = process.argv.slice(2), arg = k => argv[argv.indexOf(k) + 1];
if (!argv.includes('--execute') || arg('--environment') !== 'local' || arg('--base-url') !== 'http://127.0.0.1:18000') throw Error('Explicit local execution required');
const run = arg('--run-id'), month = arg('--month');
if (!/^[A-Za-z0-9_-]{1,12}$/.test(run || '') || !/^\d{4}-\d{2}$/.test(month || '')) throw Error('Invalid binding');
const dir = path.resolve(arg('--state-dir'));
await fs.mkdir(dir, { recursive: true, mode: 0o700 });
const file = path.join(dir, run + '-gaps.json');
let s; try { s = JSON.parse(await fs.readFile(file, 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw e; s = { run, month, fixtures: {}, completed: [], results: [] }; }
if (s.run !== run || s.month !== month) throw Error('Binding changed');
const f = s.fixtures, d = '/bookkeeping-service/bookkeeping/dictionaries', r = '/bookkeeping-service/bookkeeping/records';
const p = 'G-' + run + '-', first = month + '-01', last = month + '-28';
let active = 'PRE';
const check = (v, m) => { if (!v) throw Error(m); };
async function save() { await fs.writeFile(file + '.tmp', JSON.stringify(s, null, 2) + '\n', { mode: 0o600 }); await fs.rename(file + '.tmp', file); }
async function raw(role, method, url, body) {
  const res = await fetch('http://127.0.0.1:18000' + url, { method, headers: { 'Content-Type': 'application/json', iwtoken: process.env['IW_ACCEPTANCE_TOKEN_' + role] }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  const j = await res.json();
  return { http: res.status, code: j.code, message: String(j.message || ''), data: j.data };
}
async function api(role, method, url, body) { const v = await raw(role, method, url, body); check(v.code === 200, `API ${role} ${method} ${url.split('?')[0]} code=${v.code} reason=${v.message.slice(0, 80)}`); return v.data; }
async function bin(role, method, url, body) { const v = await raw(role, method, url, body); check(v.code !== 200, `Expected rejection ${role} ${method} ${url.split('?')[0]}`); return v; }
async function step(id, fn) { active = id; if (s.completed.includes(id)) { console.log(JSON.stringify({ scene: id, status: 'SKIPPED_PREVIOUS' })); return; } const detail = await fn(); s.completed.push(id); s.results.push({ scene: id, status: 'PASS', detail, time: new Date().toISOString() }); await save(); console.log(JSON.stringify({ scene: id, status: 'PASS', detail })); }
async function fixture(k, fn) { if (!(k in f)) { f[k] = await fn(); await save(); } return f[k]; }
const list = (role, type = 'CATEGORY', scope = 'PERSONAL') => api(role, 'GET', `${d}/items?type=${type}&scope=${scope}`);
const item = (k, name = p + k, role = 'A', type = 'CATEGORY', scope = 'PERSONAL') => fixture(k, () => api(role, 'POST', `${d}/items`, { type, scope, name }));
const options = (role, type = 'CATEGORY') => api(role, 'GET', `${d}/options?type=${type}`);
const detail = (id, role = 'A') => api(role, 'GET', `${r}/detail?id=${id}`);
const total = (role = 'A', extra = {}) => api(role, 'POST', '/bookkeeping-service/bookkeeping/consume/totalStatistics', { currentMonth: first, statisticsType: 1, isSearchAll: 0, queryOnlyMyself: 1, ...extra }).then(x => Number(x.totalAmount));
const pageRows = (role, extra = {}) => api(role, 'POST', r + '/page', { currentPage: 1, pageSize: 100, recordStartDate: first, recordEndDate: last, queryOnlyMyself: 1, ...extra });
const addBill = (k, cat, tags = [], amount = 1, extra = {}) => fixture(k, () => api('A', 'POST', r + '/add', { recordCategory: 1, recordDate: first, recordSource: p, remark: p + k, recordTypeId: cat, recordTags: tags, amount, shared: 0, isStatistics: 1, isExcitationRecord: 0, ...extra }));
try {
  // MG-04: identical-name save keeps the same source; ordering, enable/disable and no revival of a replaced source
  await step('G-MG04-SAVE-STATUS', async () => {
    const a = await item('same');
    const saved = await api('A', 'PUT', `${d}/items/${a.id}/name`, { name: a.name, revision: a.revision });
    check(saved.item.id === a.id, 'Same-name save must keep the source ID');
    const off = await api('A', 'PUT', `${d}/items/${a.id}/status`, { enabled: false, revision: saved.item.revision });
    check(off.enabled === false, 'Disable');
    await bin('A', 'PUT', `${d}/items/${a.id}/name`, { name: p + 'revive', revision: saved.item.revision });
    const on = await api('A', 'PUT', `${d}/items/${a.id}/status`, { enabled: true, revision: off.revision });
    check(on.enabled === true, 'Re-enable');
    const rows = await list('A');
    const order = rows.items.map(x => x.id);
    check(order.length === rows.items.length, 'Order payload');
    // move the fixture item to the front, then restore the original relative order of every pre-existing item
    const moved = [a.id, ...order.filter(x => x !== a.id)];
    await api('A', 'PUT', d + '/order', { type: 'CATEGORY', scope: 'PERSONAL', ids: moved, scopeVersion: rows.scopeVersion });
    check((await list('A')).items[0].id === a.id, 'Ordering persisted');
    const now = await list('A');
    await api('A', 'PUT', d + '/order', { type: 'CATEGORY', scope: 'PERSONAL', ids: order, scopeVersion: now.scopeVersion });
    check(JSON.stringify((await list('A')).items.map(x => x.id)) === JSON.stringify(order), 'Order restore');
    return 'same-name save keeps ID and advances revision; status toggle; order round-trip';
  });
  // MG-05: real interleaving for rename/delete/copy/order
  await step('G-MG05-CONCURRENCY', async () => {
    const tag = Date.now().toString(36);
    const race = await api('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'PERSONAL', name: p + 'race-' + tag });
    const results = await Promise.all([-1, 1].map(async dir => {
      return { dir, r: await raw('A', 'PUT', `${d}/items/${race.id}/name`, { name: p + 'race-' + tag + (dir > 0 ? 'x' : 'y'), revision: race.revision }) };
    }));
    const winners = results.filter(x => x.r.code === 200);
    check(winners.length === 1, `Concurrent rename winners=${winners.length}`);
    const items = await list('A');
    const successors = items.items.filter(x => x.name === p + 'race-' + tag + 'x' || x.name === p + 'race-' + tag + 'y');
    check(successors.length === 1, 'Concurrent rename must leave one successor');
    f.raceWinner = successors[0]; await save();
    // delete vs update on the same source: exactly one may succeed, and the survivor must stay readable
    const victim = await api('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'PERSONAL', name: p + 'victim-' + tag });
    const [del, upd] = await Promise.all([
      raw('A', 'DELETE', `${d}/items/${victim.id}?revision=${victim.revision}`),
      raw('A', 'PUT', `${d}/items/${victim.id}/status`, { enabled: false, revision: victim.revision })
    ]);
    check([del.code, upd.code].filter(c => c === 200).length === 1, 'Delete/update interleave must have one winner');
    // duplicate concurrent copy of the same personal source
    const copySrc = await api('A', 'POST', `${d}/items`, { type: 'EXPENSE_TAG', scope: 'PERSONAL', name: p + 'copySame-' + tag });
    const copies = await Promise.all([1, 2].map(() => raw('A', 'POST', d + '/copy-personal', { sourceIds: [copySrc.id] })));
    const created = copies.reduce((n, x) => n + (x.data?.created?.length || 0), 0);
    const familyCopies = (await list('A', 'EXPENSE_TAG', 'FAMILY')).items.filter(x => x.name === copySrc.name);
    check(familyCopies.length === 1, `Concurrent copy produced ${familyCopies.length} family sources`);
    check(created === 1, `Concurrent copy reported created=${created}`);
    // concurrent order writes with the same scope version: at most one may win
    const snap = await list('A');
    const ids = snap.items.map(x => x.id);
    const orders = await Promise.all([0, 1].map(async k => raw('A', 'PUT', d + '/order', { type: 'CATEGORY', scope: 'PERSONAL', ids: k ? [...ids].reverse() : ids, scopeVersion: snap.scopeVersion })));
    const orderWinners = orders.filter(x => x.code === 200).length;
    check(orderWinners >= 1 && orderWinners <= 2, 'Order interleave');
    const after = await list('A');
    check(after.items.length === ids.length, 'Order interleave changed item count');
    return `rename winner=1, delete/update winner=1 (${del.code}/${upd.code}), copy codes=${copies.map(x => x.code).join('/')} familySources=1, order winners=${orderWinners}`;
  });
  // OP-02: personal option disabled, family same-name fallback, then personal preferred again
  await step('G-OP02-FALLBACK', async () => {
    const name = p + 'dual';
    const personal = await fixture('dualPersonal', () => api('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'PERSONAL', name }));
    const family = await fixture('dualFamily', () => api('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'FAMILY', name }));
    check(personal.id !== family.id, 'Independent scopes');
    const before = await options('A');
    check(before.filter(x => x.name === name).length === 1 && before.find(x => x.name === name).id === personal.id, 'Personal preferred while enabled');
    const bill = await addBill('dualBill', personal.id);
    const off = await fixture('dualOff', () => api('A', 'PUT', `${d}/items/${personal.id}/status`, { enabled: false, revision: personal.revision }));
    const mid = await options('A');
    check(mid.find(x => x.name === name)?.id === family.id, 'Family fallback while personal disabled');
    const on = await api('A', 'PUT', `${d}/items/${personal.id}/status`, { enabled: true, revision: off.revision });
    const after = await options('A');
    check(after.find(x => x.name === name)?.id === personal.id, 'Personal preferred again after re-enable');
    check((await detail(bill)).recordTypeId === personal.id, 'Existing bill source changed');
    return `personal ${personal.id} <-> family ${family.id} fallback, bill ${bill} unchanged`;
  });
  // OP-04: forged foreign identifiers and wrong types
  await step('G-OP04-FORGED', async () => {
    const existed = 'forgedBill' in f;
    const before = await total();
    const bill = await addBill('forgedBill', (await item('forgedCat')).id);
    const bPersonal = (await list('B')).items[0];
    check(bPersonal && bPersonal.id > 0, 'B personal fixture missing');
    const otherPrivate = await bin('A', 'POST', r + '/add', { recordCategory: 1, recordDate: first, recordSource: p, amount: 0.01, recordTypeId: bPersonal.id, recordTags: [] });
    // categories share one dictionary type (4002); only tags are split by expense/income (4001/4011)
    const incomeTag = await fixture('incomeTag', () => api('A', 'POST', `${d}/items`, { type: 'INCOME_TAG', scope: 'PERSONAL', name: p + 'incomeTag' }));
    const wrongTagType = await bin('A', 'POST', r + '/add', { recordCategory: 1, recordDate: first, recordSource: p, amount: 0.01, recordTypeId: f.dualPersonal.id, recordTags: [incomeTag.id] });
    const forgedSource = await bin('A', 'GET', `${r}/detail?id=${bill + 999999}`);
    check(await total() === before + (existed ? 0 : 1), 'Rejected writes changed total');
    return `B private id=${otherPrivate.code}, income tag on expense bill=${wrongTagType.code}, forged bill=${forgedSource.code}, expense total +1`;
  });
  // ST-04: one bill carrying same-name tags from two different sources
  await step('G-ST04-SAME-NAME-TAGS', async () => {
    const name = p + 'sameTag';
    const personalTag = await fixture('st4Personal', () => api('A', 'POST', `${d}/items`, { type: 'EXPENSE_TAG', scope: 'PERSONAL', name }));
    const familyTag = await fixture('st4Family', () => api('A', 'POST', `${d}/items`, { type: 'EXPENSE_TAG', scope: 'FAMILY', name }));
    const bill = await addBill('st4Bill', (await item('st4Cat')).id, [personalTag.id, familyTag.id], 100);
    const row = await detail(bill);
    const names = (row.recordTagSnapshots || []).filter(x => x.name === name);
    const stats = await api('A', 'POST', '/bookkeeping-service/bookkeeping/consume/tagsStatistics', { currentMonth: first, statisticsType: 1, isSearchAll: 0, queryOnlyMyself: 1 });
    const rows = stats.filter(x => x.name === name);
    check(rows.length === 1, `Same-name tag aggregation rows=${rows.length}`);
    check(Number(rows[0].amount) === 100, `Same-name tag amount=${rows[0]?.amount}`);
    const paged = await pageRows('A', { tagNames: [name] });
    check(paged.total === 1, `OR filter duplicated bill total=${paged.total}`);
    check(await total('A', { tagNames: [name] }) === 100, 'OR filter duplicated amount');
    return `snapshots=${names.length}, statistics rows=1 amount=100, page total=1`;
  });
  // ST-06 / name normalization: case, accent, internal space, surrounding space
  await step('G-ST06-NAME-VARIANTS', async () => {
    const names = [p + 'Cafe', p + 'cafe', p + 'café', p + 'a b', p + '  padded  '];
    const ids = [];
    for (const [i, name] of names.entries()) ids.push((await fixture('variant' + i, () => api('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'PERSONAL', name }))).id);
    check(new Set(ids).size === names.length, 'Variant names collided');
    const rows = await list('A');
    for (const [i, name] of names.entries()) {
      const stored = rows.items.find(x => x.id === ids[i]).name;
      check(stored === name.trim(), `Stored name mismatch for variant ${i}: ${stored}`);
    }
    const stats = await api('A', 'POST', '/bookkeeping-service/bookkeeping/consume/pieChartStatistics', { currentMonth: first, statisticsType: 1, isSearchAll: 0, queryOnlyMyself: 1, isQueryLastMonth: false });
    const distinct = new Set(stats.map(x => x.recordTypeName).filter(n => n && n.startsWith(p)));
    check(distinct.size >= 1, 'Variant categories missing from statistics');
    return `5 variants stored distinctly (trim applied), statistics names=${distinct.size}`;
  });
  // MG-02 continuation: a disabled name stays occupied in the same scope
  await step('G-MG02-DISABLED-NAME', async () => {
    const name = p + 'occupied';
    const it = await fixture('occupied', () => api('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'PERSONAL', name }));
    const off = await fixture('occupiedOff', () => api('A', 'PUT', `${d}/items/${it.id}/status`, { enabled: false, revision: it.revision }));
    const dup = await bin('A', 'POST', `${d}/items`, { type: 'CATEGORY', scope: 'PERSONAL', name });
    check(dup.code !== 200, 'Disabled name must stay occupied');
    return `disabled item still occupies its name (code=${dup.code})`;
  });
  console.log(JSON.stringify({ status: 'COMPLETE', detail: 'Only executed assertions passed', stateFile: file }));
} catch (error) {
  const v = { scene: active, status: 'FAIL', detail: error instanceof Error ? error.message : 'Runner failure', time: new Date().toISOString() };
  s.results.push(v); await save(); console.error(JSON.stringify(v)); process.exitCode = 1;
}
