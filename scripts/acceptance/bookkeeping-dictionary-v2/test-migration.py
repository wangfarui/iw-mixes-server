#!/usr/bin/env python3
"""Real MySQL regression tests. Requires a disposable, private Unix-socket MySQL instance.
Never connects via TCP or reads the application's database credentials.
"""
import argparse
import hashlib
import re
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SQL = ROOT / 'iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--mysql', required=True)
p.add_argument('--socket', required=True)
p.add_argument('--backup', type=Path, required=True, help='Read-only source dump; imported into owned test schemas')
a = p.parse_args()
assert a.socket.startswith('/private/tmp/iw-dict-v2-mysql-'), 'Only a disposable test socket is accepted'
BASE = [a.mysql, '--no-defaults', '--protocol=SOCKET', '--socket=' + a.socket, '-uroot', '--batch', '--raw']

def run(sql, db=None, ok=True):
    r = subprocess.run(BASE + ([db] if db else []), input=sql, text=True, capture_output=True)
    if ok and r.returncode:
        raise AssertionError(r.stderr + '\n' + r.stdout[-2000:])
    return r

def script(name, db, ok=True):
    return run((SQL / name).read_text(), db, ok)

server = run('SELECT @@datadir,@@skip_networking,@@port;').stdout.splitlines()[-1].split('\t')
assert server[0].startswith('/private/tmp/iw-dict-v2-mysql-') and server[1:] == ['1','0'], 'Requires an isolated data directory with TCP disabled'

# No DROP DATABASE and no reuse of existing schemas: unexpected objects abort the test.
PREFIX = 'dv2_test_' + uuid.uuid4().hex[:6] + '_'
seed = PREFIX + 'seed'
run('CREATE DATABASE ' + seed + ' CHARACTER SET utf8mb4;')
run(a.backup.read_text(), seed)
original = PREFIX + 'original'
run('CREATE DATABASE ' + original + ' CHARACTER SET utf8mb4;')
run(a.backup.read_text(), original)
print('PASS restore full local backup into isolated MySQL')

# These deletions alter ONLY the disposable test fixture, never the source database.
run("""
DELETE FROM bookkeeping_records WHERE dictionary_snapshot_status=0;
DELETE FROM base_dict WHERE scope_type=1;
DROP TABLE bookkeeping_dictionary_migration_baseline,bookkeeping_family_dict_state,bookkeeping_record_tag_snapshot;
ALTER TABLE base_dict DROP INDEX uk_bookkeeping_dict_name,DROP INDEX uk_dict_previous,DROP INDEX idx_bookkeeping_scope,
 DROP COLUMN active_name_key,DROP COLUMN scope_type,DROP COLUMN family_group_id,DROP COLUMN previous_id,DROP COLUMN revision;
ALTER TABLE bookkeeping_records DROP COLUMN record_type_id,DROP COLUMN record_type_name,DROP COLUMN dictionary_snapshot_status;
ALTER TABLE bookkeeping_budget DROP COLUMN record_type_id,DROP COLUMN record_type_name;
ALTER TABLE bookkeeping_actions DROP COLUMN record_type_id;
""", seed)
for table,col in [('points_task_plan','bookkeeping_template'),('points_task_bookkeeping','template_snapshot')]:
    run(f"""UPDATE {table} p LEFT JOIN base_dict d ON d.id=JSON_EXTRACT(p.{col},'$.recordTypeId')
SET p.{col}=JSON_SET(JSON_REMOVE(p.{col},'$.recordTypeId','$.recordTypeName','$.recordTagSnapshots'),
'$.schemaVersion',CAST(1 AS UNSIGNED),'$.recordCategory',CAST(JSON_UNQUOTE(JSON_EXTRACT(p.{col},'$.recordCategory')) AS UNSIGNED),
'$.recordType',d.dict_code,'$.migrationProbe','must survive') WHERE p.{col} IS NOT NULL;""", seed)

tables = ['base_dict','base_dict_business_relation','bookkeeping_records','bookkeeping_wallet','bookkeeping_budget',
          'bookkeeping_actions','auth_user','auth_family_group','auth_family_member','points_task_plan','points_task_basics','points_task_bookkeeping']

def fresh(name):
    db = PREFIX + name
    run('CREATE DATABASE ' + db + ' CHARACTER SET utf8mb4;')
    for table in tables:
        cols = run(f"SELECT GROUP_CONCAT(CONCAT('`',column_name,'`') ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='{seed}' AND table_name='{table}' AND extra NOT LIKE '%STORED GENERATED%' AND extra NOT LIKE '%VIRTUAL GENERATED%';").stdout.splitlines()[-1]
        run(f'CREATE TABLE {table} LIKE {seed}.{table}; INSERT INTO {table} ({cols}) SELECT {cols} FROM {seed}.{table};', db)
    return db

def snapshot(db):
    ts = tables + ['bookkeeping_record_tag_snapshot','bookkeeping_family_dict_state','bookkeeping_dictionary_migration_baseline']
    result = b''
    for table in ts:
        order = 'family_group_id' if table.endswith('family_dict_state') else 'metric' if table.endswith('migration_baseline') else 'id'
        result += run(f'SELECT * FROM {table} ORDER BY {order};', db).stdout.encode()
    return hashlib.sha256(result).hexdigest()

def overall(db, expected):
    out = script('production/02-verify.sql', db).stdout
    assert f'OVERALL\t0\tPASS' in out if expected else bool(re.search(r'OVERALL\t[1-9]\d*\tFAIL',out)), out
    return out

# Existing partial migration must fail before schema/data modification.
script('production/02-verify.sql', original)
before = snapshot(original)
r = script('production/01-migrate.sql', original, False)
assert r.returncode != 0 and 'invalid historical bill category' in r.stderr
assert before == snapshot(original)
print('PASS actual 20 invalid historical bills block migration without data changes')

db = fresh('happy')
script('00-preflight.sql', db)
r = script('production/01-migrate.sql', db)
assert re.search(r'MIGRATION_PASS\t150\t11\t\d+',r.stdout),r.stdout
out = overall(db, True)
assert '150\t150\t25719.64' in out
for table,col in [('points_task_plan','bookkeeping_template'),('points_task_bookkeeping','template_snapshot')]:
    assert run(f"SELECT COUNT(*) FROM {table} WHERE {col} IS NOT NULL AND JSON_UNQUOTE(JSON_EXTRACT({col},'$.migrationProbe'))<>'must survive';",db).stdout.strip().endswith('0')
# Direct unsigned JSON production output must be accepted by the verifier.
assert 'UNSIGNED INTEGER' in run("SELECT JSON_TYPE(JSON_EXTRACT(template_snapshot,'$.recordTypeId')) FROM points_task_bookkeeping LIMIT 1;",db).stdout
before = snapshot(db)
r = script('production/01-migrate.sql', db)
assert 'MIGRATION_PASS\t0\t0\t0' in r.stdout,r.stdout
assert before == snapshot(db)
overall(db, True)
print('PASS first migration: 150 bills, 11 unsigned-schema1 templates; unknown JSON fields preserved')
print('PASS repeat migration: zero counts and exact all-column hashes unchanged')

# Deliberately break future config after record checks; all bill/tag/template DML must roll back.
db = fresh('rollback')
run("UPDATE bookkeeping_actions SET record_tags='999999999' WHERE id=(SELECT n FROM (SELECT MIN(id) n FROM bookkeeping_actions) v);",db)
r = script('production/01-migrate.sql', db, False)
assert r.returncode != 0 and 'backfill issues' in r.stderr,r.stdout+r.stderr
assert run('SELECT COUNT(*) FROM bookkeeping_records WHERE dictionary_snapshot_status<>0;',db).stdout.strip().endswith('0')
assert run('SELECT COUNT(*) FROM bookkeeping_record_tag_snapshot;',db).stdout.strip().endswith('0')
assert run('SELECT COUNT(*) FROM bookkeeping_family_dict_state;',db).stdout.strip().endswith('0')
assert run("SELECT COUNT(*) FROM points_task_bookkeeping WHERE JSON_EXTRACT(template_snapshot,'$.schemaVersion')<>1;",db).stdout.strip().endswith('0')
print('PASS invalid future tag causes error and rolls back records, tags, templates and family initialization')

# Original phased 02 must also reject partial success.
r = script('02-backfill.sql', db, False)
assert r.returncode != 0 and 'all DML in this run rolled back' in r.stderr,r.stdout+r.stderr
assert run('SELECT COUNT(*) FROM bookkeeping_records WHERE dictionary_snapshot_status<>0;',db).stdout.strip().endswith('0')
print('PASS standalone 02 also rolls back instead of committing partial success')

db = fresh('json_string')
run("UPDATE points_task_plan SET bookkeeping_template=JSON_SET(bookkeeping_template,'$.recordTags',JSON_ARRAY('1291')) WHERE id=4;",db)
r = script('production/01-migrate.sql', db, False)
assert r.returncode != 0 and 'template tag source' in r.stdout,r.stdout+r.stderr
print('PASS string tag IDs remain rejected; accepting unsigned integers does not loosen JSON validation')

db = fresh('missing_source')
run('UPDATE bookkeeping_records SET record_type=127 WHERE id=(SELECT n FROM (SELECT MIN(id) n FROM bookkeeping_records) v);',db)
r = script('production/01-migrate.sql', db, False)
assert r.returncode != 0 and 'source missing/ambiguous' in r.stderr
assert run("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_dict' AND column_name='scope_type';",db).stdout.strip().endswith('0')
print('PASS missing historical source blocks before schema migration')

# Same index name is not sufficient evidence that an existing schema is compatible.
db = PREFIX + 'happy'
run('ALTER TABLE base_dict DROP INDEX idx_bookkeeping_scope, ADD INDEX idx_bookkeeping_scope(user_id);',db)
out = overall(db, False)
assert 'schema_indexes\t1\tFAIL' in out
r = script('production/01-migrate.sql', db, False)
assert r.returncode != 0 and 'verification failed before commit' in r.stderr
print('PASS existing wrong index definition fails verification and blocks commit')
run('ALTER TABLE base_dict DROP INDEX idx_bookkeeping_scope, ADD INDEX idx_bookkeeping_scope(scope_type,family_group_id,user_id,dict_type,deleted);',db)
run("DELETE FROM bookkeeping_dictionary_migration_baseline WHERE metric='wallet';",db)
out = overall(db, False)
assert 'baseline_missing\t1\tFAIL' in out
r = script('production/01-migrate.sql', db, False)
assert r.returncode != 0 and 'baseline incomplete' in r.stderr
assert run("SELECT COUNT(*) FROM bookkeeping_dictionary_migration_baseline WHERE metric='wallet';",db).stdout.strip().endswith('0')
print('PASS missing baseline row fails verification and cannot be silently regenerated by migration')
# Validate the exact local quarantine in the isolated restored copy. Only its port
# guard is adapted from 3306 to the private server's 0; the delivered file stays guarded.
local_cleanup = (SQL / 'local-only/01-quarantine-test-records.sql').read_text()
r = run(local_cleanup, original, False)
assert r.returncode != 0 and 'local-only cleanup' in r.stderr
print('PASS local-only quarantine rejects the wrong server port')
local_cleanup = local_cleanup.replace('@@port<>3306', '@@port<>0').replace("DATABASE()<>'iw_mixes'", "DATABASE()<>'" + original + "'")
wallet_before = run('SELECT * FROM bookkeeping_wallet ORDER BY id;', original).stdout
r = run(local_cleanup, original)
assert 'LOCAL_TEST_QUARANTINE_PASS\t20\t1149.23' in r.stdout,r.stdout
assert wallet_before == run('SELECT * FROM bookkeeping_wallet ORDER BY id;', original).stdout
r = script('production/01-migrate.sql', original)
assert 'MIGRATION_PASS\t0\t0\t0' in r.stdout,r.stdout
overall(original, True)
before = snapshot(original)
r = run(local_cleanup, original)
assert 'ALREADY_QUARANTINED\t0' in r.stdout,r.stdout
assert before == snapshot(original)
print('PASS quarantine archives exactly 20 bills / 1149.23, preserves wallet and rebases only local records cohort')
print('PASS quarantined source copy: all verification checks pass; repeat cleanup and migration change no data')
print('ALL MYSQL REGRESSION CHECKS PASSED; source application database untouched')
