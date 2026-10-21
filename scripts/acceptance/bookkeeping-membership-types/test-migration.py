#!/usr/bin/env python3
"""Real MySQL regression using only generated fixtures in a newly created temporary schema.
Requires --execute and a private disposable Unix socket under /private/tmp/iw-mtype-.
No application credentials, source database, existing schemas or business data are read.
"""
import argparse
import hashlib
import re
import subprocess
import uuid
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--execute',action='store_true',required=True)
p.add_argument('--socket',required=True)
p.add_argument('--mysql',default='/usr/local/mysql/bin/mysql')
a=p.parse_args()
assert a.socket.startswith('/private/tmp/iw-mtype-') and a.socket.endswith('.sock'), 'Disposable mtype socket required'
ROOT=Path(__file__).resolve().parents[3]
SQL=ROOT/'docs/plans/bookkeeping-membership-types-20261021/sql'
base=[a.mysql,'--no-defaults','--protocol=SOCKET','--socket='+a.socket,'-uroot','--default-character-set=utf8mb4','--batch','--raw']
def run(sql,db=None,ok=True):
    result=subprocess.run(base+([db] if db else []),input=sql,text=True,capture_output=True)
    if ok and result.returncode: raise AssertionError(result.stderr+'\n'+result.stdout[-1500:])
    return result
server=run('SELECT @@datadir,@@skip_networking,@@port;').stdout.splitlines()[-1].split('\t')
assert server[0].startswith('/private/tmp/iw-mtype-') and server[1:]==['1','0'], 'Requires an isolated TCP-disabled server'
PREFIX='mtype_'+uuid.uuid4().hex[:8]+'_'
def table(path,name):
    text=(ROOT/path).read_text()
    return re.search(r'create table '+name+r'\s*\([\s\S]*?;',text,re.I).group(0)
def fresh(suffix):
    db=PREFIX+suffix
    run('CREATE DATABASE '+db+' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
    d=table('iw-web/scripts/iw-web-init.sql','base_dict')
    d='\n'.join(line for line in d.splitlines() if 'membership_active_name_key' not in line and 'uk_membership_dict_name' not in line)
    s=table('iw-packaging-parent/iw-bookkeeping/scripts/iw-bookkeeping-init.sql','bookkeeping_membership_subscription')
    s='\n'.join(line for line in s.splitlines() if not any(x in line for x in ['membership_type_id','membership_type_name','membership_type_snapshot_status','idx_membership_type_snapshot']))
    run(d+'\n'+s,db)
    return db

def migrate(db,ok=True,approved=True):
    return run(('SET @iw_mtype_migration_approved=1;\n' if approved else '')+(SQL/'01-migrate.sql').read_text(),db,ok)
def verify(db,ok=True): return run((SQL/'02-verify.sql').read_text(),db,ok)
def snapshot(db):
    payload=run('SELECT * FROM base_dict ORDER BY id;SELECT * FROM bookkeeping_membership_subscription ORDER BY id;SELECT * FROM bookkeeping_membership_migration_baseline ORDER BY id;SELECT * FROM bookkeeping_membership_migration_state ORDER BY id;',db).stdout
    return hashlib.sha256(payload.encode()).hexdigest()

db=fresh('happy')
run("""INSERT INTO base_dict(id,dict_type,dict_code,dict_name,user_id,dict_status,deleted) VALUES
(1,4004,1,'视频',7,1,0),(2,4004,1,'音乐',8,1,0),(3,4004,2,'停用',7,0,0),
(4,4004,3,'删除',7,1,1),(5,4004,4,'重复一',7,1,0),(6,4004,4,'重复二',7,1,0),
(7,4004,5,'曾用',7,1,1),(8,4004,5,'重用',7,1,0),(9,4004,0,'新ID',7,1,0),
(10,4004,1,'模板',0,1,0);
INSERT INTO bookkeeping_membership_subscription(id,user_id,membership_type,membership_name,amount,billing_cycle,start_date,end_date,auto_renew) VALUES
(1,7,1,'AT-MTYPE-视频',12.50,1,'2026-10-21','2026-11-21',1),
(2,8,1,'AT-MTYPE-音乐',9.99,2,'2026-10-21','2027-10-21',0),
(3,7,2,'AT-MTYPE-停用',2,1,'2026-10-21','2026-11-21',0),
(4,7,3,'AT-MTYPE-删除',3,1,'2026-10-21','2026-11-21',0),
(5,7,4,'AT-MTYPE-重复',4,1,'2026-10-21','2026-11-21',0),
(6,7,5,'AT-MTYPE-重用',5,1,'2026-10-21','2026-11-21',0),
(7,7,9,'AT-MTYPE-缺失',6,1,'2026-10-21','2026-11-21',0),
(8,7,0,'AT-MTYPE-零编码',7,1,'2026-10-21','2026-11-21',0),
(9,999,1,'AT-MTYPE-不能用模板',8,1,'2026-10-21','2026-11-21',0);""",db)
assert migrate(db,False,False).returncode!=0
assert run("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND column_name='membership_type_id';",db).stdout.strip().endswith('0')
print('PASS explicit stop-write approval guard; no schema change before approval')
run((SQL/'00-preflight.sql').read_text(),db)
migrate(db)
assert 'MEMBERSHIP_MIGRATION_PASS\t9\t3\t6' in verify(db).stdout
assert run('SELECT membership_type_name FROM bookkeeping_membership_subscription WHERE id=1;',db).stdout.strip().endswith('视频')
assert run('SELECT membership_type_name FROM bookkeeping_membership_subscription WHERE id=2;',db).stdout.strip().endswith('音乐')
print('PASS same legacy code maps by owner; unique disabled source verified; deleted/reused/duplicate/missing/zero/template remain pending')
before=snapshot(db);out=migrate(db).stdout;verify(db);assert before==snapshot(db)
print('PASS rerun migration and verification: all dictionary/subscription/baseline columns unchanged')
run("INSERT INTO base_dict(dict_type,dict_code,dict_name,user_id) VALUES(4004,0,REPEAT('🍚',32),7);",db)
assert run("INSERT INTO base_dict(dict_type,dict_code,dict_name,user_id) VALUES(4004,0,REPEAT('🍚',32),7);",db,False).returncode!=0
assert run("INSERT INTO base_dict(dict_type,dict_code,dict_name,user_id) VALUES(4004,0,REPEAT('🍚',33),7);",db,False).returncode!=0
run("INSERT INTO base_dict(dict_type,dict_code,dict_name,user_id) VALUES(4004,0,'AbC',7),(4004,0,'abc',7);",db)
print('PASS unique current names, case sensitivity and 32/33 emoji schema bounds')
run('ALTER TABLE bookkeeping_membership_subscription DROP INDEX idx_membership_type_snapshot;',db)
assert verify(db,False).returncode!=0
run('ALTER TABLE bookkeeping_membership_subscription ADD INDEX idx_membership_type_snapshot(user_id,deleted,membership_type_snapshot_status,membership_type_name);',db)
run('UPDATE bookkeeping_membership_subscription SET amount=amount+1 WHERE id=1;',db)
assert verify(db,False).returncode!=0
print('PASS missing index and original-field changes fail verification')
# Family rows must block before adding columns; never silently convert or delete them.
bad=fresh('family');run("INSERT INTO base_dict(dict_type,dict_code,dict_name,user_id,scope_type,family_group_id) VALUES(4004,1,'家庭异常',0,1,9);",bad)
assert migrate(bad,False).returncode!=0
assert run("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bookkeeping_membership_subscription' AND column_name='membership_type_id';",bad).stdout.strip().endswith('0')
print('PASS unexpected family source blocks before schema/backfill')
print('ALL MYSQL FIXTURE CHECKS PASSED; disposable schemas:',PREFIX+'happy',PREFIX+'family')
print('No application database was read or changed. Stop and remove only the owned disposable server directory after evidence capture.')
