#!/usr/bin/env python3
"""Verify immediate generation in a disposable MySQL database; no user data is read."""
import argparse
import concurrent.futures
from pathlib import Path
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-client', default='mysql')
    parser.add_argument('--socket', required=True)
    parser.add_argument('--user', default='root')
    args = parser.parse_args()
    command = [args.mysql_client, '--no-defaults', f'--socket={args.socket}',
               f'--user={args.user}', '--batch', '--skip-column-names']
    scripts = Path(__file__).resolve().parent
    database = 'iw_plan_generation_verify_' + uuid.uuid4().hex[:12]

    def sql(statement, error=None, global_scope=False):
        result = subprocess.run(command + ([] if global_scope else [database]),
                                input=statement, text=True, capture_output=True)
        if error:
            assert result.returncode and error in result.stderr, result.stderr
        else:
            assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    def insert(request_id, date='2026-10-13', plan_id=1):
        request = 'NULL' if request_id is None else "'" + request_id + "'"
        return ("INSERT INTO points_task_basics(task_name,user_id,source_plan_id,source_plan_date,source_request_id) "
                f"VALUES ('generated',1,{plan_id},'{date}',{request});")

    sql(f'CREATE DATABASE {database} CHARACTER SET utf8mb4;', global_scope=True)
    try:
        historical = (scripts / 'iw-points-init.sql').read_text().split('-- 任务计划完成时自动记账（v1）')[0]
        sql(historical)
        sql((scripts / 'iw-points-migrate-20261010-task-bookkeeping.sql').read_text())
        sql("INSERT INTO points_task_basics(task_name,user_id,source_plan_id,source_plan_date,deleted) "
            "VALUES ('historical',1,1,'2026-10-13',1);")
        sql((scripts / 'iw-points-migrate-20261013-plan-generate-now.sql').read_text())
        assert sql('SELECT scheduled_plan_date,source_request_id IS NULL FROM points_task_basics;') == '2026-10-13\t1'
        sql(insert(None), error='1062')
        sql(insert('manual_request_01'))
        sql(insert('manual_request_02'))
        sql(insert('manual_request_01'), error='1062')
        sql(insert('manual_request_01', '2026-10-14'), error='1062')
        sql("UPDATE points_task_basics SET deleted=1 WHERE source_request_id='manual_request_01';")
        sql(insert('manual_request_01'), error='1062')
        sql(insert(None, '2026-10-14'))
        assert sql('SELECT COUNT(*) FROM points_task_basics;') == '4'
        sql('START TRANSACTION;' + insert('rollback_request_01') + 'ROLLBACK;')
        assert sql("SELECT COUNT(*) FROM points_task_basics WHERE source_request_id='rollback_request_01';") == '0'
        for request in (None, 'concurrent_request_01'):
            statement = insert(request, plan_id=2)
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda _: subprocess.run(command + [database], input=statement,
                                                                text=True, capture_output=True), range(2)))
            assert sum(result.returncode == 0 for result in results) == 1
            assert all(result.returncode == 0 or '1062' in result.stderr for result in results)
        # Verify the documented index rename for old installations, and the fresh-install schema too.
        drop_tables = ('DROP TABLE points_task_bookkeeping, points_task_basics, points_task_plan, '
                       'points_task_fixed, points_task_relation, points_task_group, points_records, points_total;')
        sql(drop_tables)
        sql(historical)
        sql((scripts / 'iw-points-migrate-20261010-task-bookkeeping.sql').read_text())
        sql('ALTER TABLE points_task_basics RENAME INDEX uk_task_plan_period TO uk_source_plan_date;')
        sql('ALTER TABLE points_task_basics RENAME INDEX uk_source_plan_date TO uk_task_plan_period;')
        sql((scripts / 'iw-points-migrate-20261013-plan-generate-now.sql').read_text())
        sql(insert(None))
        sql(insert('alternate_request_01'))
        sql(insert(None), error='1062')
        sql(drop_tables)
        sql((scripts / 'iw-points-init.sql').read_text())
        sql(insert(None))
        sql(insert('fresh_request_001'))
        sql(insert(None), error='1062')
        sql(insert('fresh_request_001'), error='1062')
        print('PASS: migration, fresh schema, manual/automatic coexistence, cross-day/deleted retries, concurrency, rollback')
    finally:
        sql(f'DROP DATABASE {database};', global_scope=True)


if __name__ == '__main__':
    main()
