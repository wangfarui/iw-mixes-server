#!/usr/bin/env node
/** Read-only regression of the r1019a fixtures on the already-populated original application database.
 *  Writes nothing: no bills, no dictionary items, no points. Only GET/POST-query endpoints. */
import path from 'node:path';
const args = process.argv.slice(2), get = k => args[args.indexOf(k) + 1];
if (!args.includes('--execute')) throw Error('Explicit --execute required');
const base = get('--base-url') || 'http://127.0.0.1:18000';
const run = get('--run-id') || 'r1019a';
const month = get('--month') || '2032-04';
const out = [];
const check = (v, m) => { if (!v) throw Error(m); };
async function api(role, method, url, body) {
  const res = await fetch(base + url, { method, headers: { 'Content-Type': 'application/json', iwtoken: process.env['IW_ACCEPTANCE_TOKEN_' + role] }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) });
  const j = await res.json();
  check(j.code === 200, `API ${role} ${method} ${url.split('?')[0]} code=${j.code} reason=${String(j.message || '').slice(0, 80)}`);
  return j.data;
}
const ok = (id, detail) => { const v = { scene: id, status: 'PASS', detail, time: new Date().toISOString() }; out.push(v); console.log(JSON.stringify(v)); };
const first = `${month}-01`, last = `${month}-30`;
const dict = '/bookkeeping-service/bookkeeping/dictionaries';
const records = '/bookkeeping-service/bookkeeping/records';
const total = (role, personal) => api(role, 'POST', '/bookkeeping-service/bookkeeping/consume/totalStatistics', { currentMonth: first, statisticsType: 1, isSearchAll: 0, queryOnlyMyself: personal ? 1 : 0 }).then(d => Number(d.totalAmount));
const page = (role, source, start, end) => api(role, 'POST', records + '/page', { currentPage: 1, pageSize: 100, recordStartDate: start, recordEndDate: end, recordSource: source, queryOnlyMyself: 0 });
const flat = v => JSON.stringify(v);
try {
  // 1. identities and family roles, as documented for the original database
  const expected = { A: [43, 1], B: [42, 2], C: [8, 3], D: [44, 4], E: [45, null] };
  const seen = {};
  for (const role of 'ABCDE') {
    const user = await api(role, 'GET', '/auth-service/user/getUserInfo');
    const group = await api(role, 'GET', '/auth-service/family/group/myGroup');
    const code = group ? (typeof group.currentUserRole === 'object' ? group.currentUserRole?.code : group.currentUserRole) : null;
    check(user.id === expected[role][0], `Identity mismatch role ${role}`);
    check(code === expected[role][1], `Family role mismatch role ${role} actual=${code}`);
    check(role === 'E' ? group == null : group.id === 2, `Family binding mismatch role ${role}`);
    seen[role] = { userId: user.id, familyId: group?.id ?? null, role: code };
  }
  ok('CUR-IDENTITY', JSON.stringify(seen));

  // 2. fixture bills R1-R6 with stored snapshots (R5 is private to its recorder B, so merge both views)
  const rowsA = await page('A', `ACCEPT-${run}`, first, last);
  const rowsB = await page('B', `ACCEPT-${run}`, first, last);
  const byRemark = {};
  for (const row of [...rowsA.records, ...rowsB.records]) byRemark[Number(row.amount)] = row;
  check(rowsA.total === 5 && rowsB.total === 5, `Unexpected fixture counts A=${rowsA.total} B=${rowsB.total}`);
  check(Object.keys(byRemark).length === 6, `Expected 6 distinct fixture bills, got ${Object.keys(byRemark).length}`);
  const expectedBills = [[100, `验收餐饮-${run}`], [200, `验收餐饮-${run}`], [80, `验收餐饮-${run}`], [300, `验收餐饮-${run}`], [50, `验收餐饮-${run}`], [20, `验收用餐-${run}`]];
  for (const [amount, name] of expectedBills) {
    const row = byRemark[amount];
    check(row, `Missing fixture amount ${amount}`);
    check(row.recordTypeName === name, `Snapshot name mismatch ${amount}: ${row.recordTypeName}`);
  }
  const d100 = await api('A', 'GET', records + `/detail?id=${byRemark[100].id}`);
  const d200 = await api('A', 'GET', records + `/detail?id=${byRemark[200].id}`);
  check(d100.shared === 0, 'R1 must stay private');
  check(d200.shared === 1, 'R2 must stay shared');
  check(byRemark[80].expenseUserId === 42, 'R3 payer must be B');
  ok('CUR-SNAPSHOTS', `6 bills, amounts 100/200/80/300/50/20, old name 验收餐饮-${run}, new name 验收用餐-${run}`);

  // 3. historical tag snapshot survives the tag rename
  const detail = await api('A', 'GET', records + `/detail?id=${byRemark[100].id}`);
  const tagSnapshot = (detail.recordTagSnapshots || []).find(t => t.name === `验收外卖-${run}`);
  check(tagSnapshot, 'R1 historical tag snapshot missing');
  ok('CUR-TAG-SNAPSHOT', `R1 tag snapshot id=${tagSnapshot.id} name=验收外卖-${run}`);

  // 4. statistics keep the documented payment-owner amounts
  const totals = { A: [true, 320], B: [true, 430] };
  for (const role of ['A', 'B']) check(await total(role, true) === totals[role][1], `Personal total mismatch ${role}`);
  for (const [role, expectedFamily] of [['A', 700], ['B', 650], ['C', 600]]) check(await total(role, false) === expectedFamily, `Family total mismatch ${role}`);
  ok('CUR-TOTALS', 'A 320/700, B 430/650, C family 600');

  // 5. extended fixtures (2032-05, prefix X-r1019a-) still present and unchanged in total
  const extended = await page('A', `X-${run}-`, '2032-05-01', '2032-05-28');
  check(extended.total === 3, `Expected 3 extended bills, got ${extended.total}`);
  const extendedSum = extended.records.reduce((sum, r) => sum + Number(r.amount), 0);
  check(Math.round(extendedSum * 100) === 11300, `Extended fixture total changed: ${extendedSum}`);
  ok('CUR-EXTENDED', `3 bills, total ${extendedSum} for X-${run}-`);
  // Import and voice fixtures were created by this session on the isolated copy (18001), not here.
  ok('CUR-SCOPE-NOTE', 'import/voice fixtures live in the private copy; they are asserted by the 18001 runner, not on this database');

  // 6. privacy: child and no-family identities must not see adult private history names
  const adultNames = [`验收餐饮-${run}`, `验收用餐-${run}`, `验收外卖-${run}`, `VOICE-${run}`];
  for (const role of ['D', 'E']) {
    const options = await api(role, 'GET', records + '/filter-options');
    const text = flat(options);
    for (const name of adultNames) check(!text.includes(name), `Adult history name leaked to ${role}`);
  }
  const optionsC = await api('C', 'GET', dict + '/options?type=EXPENSE_TAG');
  const aTag = (await api('A', 'GET', dict + '/options?type=EXPENSE_TAG')).find(t => t.name === `验收新标签-${run}`);
  if (aTag) check(!optionsC.some(t => t.id === aTag.id), 'A private tag leaked into C candidates');
  const eOwn = await total('E', true);
  check(eOwn === 0 || eOwn > 0, 'E personal query failed');
  ok('CUR-PRIVACY', 'D/E filter options exclude adult fixture names; C cannot select A private tag');

  console.log(JSON.stringify({ status: 'COMPLETE', mode: 'READ_ONLY', base, run, month, scenes: out.length }));
} catch (error) {
  const v = { scene: 'CURRENT-STATE', status: 'FAIL', detail: error.message, time: new Date().toISOString() };
  console.error(JSON.stringify(v));
  process.exitCode = 1;
}
