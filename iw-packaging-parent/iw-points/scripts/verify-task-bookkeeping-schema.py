#!/usr/bin/env python3
"""Verify migration and durable deduplication in a disposable MySQL 8 database.

Only creates/drops a generated iw_task_bookkeeping_verify_* database. The
connection account must have CREATE/DROP DATABASE privilege; no user data is read.
Example: python3 verify-task-bookkeeping-schema.py --socket /tmp/mysql.sock
"""
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
    parser.add_argument('--defaults-extra-file', help='Optional protected MySQL credentials file')
    args = parser.parse_args()
    command = [args.mysql_client]
    command += ([f'--defaults-extra-file={args.defaults_extra_file}']
                if args.defaults_extra_file else ['--no-defaults'])
    command += [f'--socket={args.socket}', f'--user={args.user}', '--batch', '--skip-column-names']
    database = 'iw_task_bookkeeping_verify_' + uuid.uuid4().hex[:12]
    scripts = Path(__file__).resolve().parent

    def sql(statement, expected_error=None, global_scope=False):
        result = subprocess.run(command + ([] if global_scope else [database]),
                                input=statement, text=True, capture_output=True)
        if expected_error:
            assert result.returncode and expected_error in result.stderr, result.stderr
        else:
            assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    sql(f'CREATE DATABASE {database} CHARACTER SET utf8mb4;', global_scope=True)
    try:
        # Apply migration against historical schema and populated old rows.
        migration = (scripts / 'iw-points-migrate-20261010-task-bookkeeping.sql').read_text()
        initial = (scripts / 'iw-points-init.sql').read_text().split('-- 任务计划完成时自动记账（v1）')[0]
        sql(initial)
        sql("INSERT INTO points_task_plan(task_name,plan_cycle,plan_date,next_plan_date,user_id) "
            "VALUES ('historical',1,'2026-10-10','2026-10-10',1); "
            "INSERT INTO points_task_basics(task_name,user_id) VALUES ('historical',1);")
        sql(migration)
        assert sql('SELECT bookkeeping_enabled,bookkeeping_template IS NULL FROM points_task_plan;') == '0\t1'
        assert sql('SELECT source_plan_id IS NULL,source_plan_date IS NULL,completion_reward_points FROM points_task_basics;') == '1\t1\t0'
        assert sql('SELECT COUNT(*) FROM points_task_bookkeeping;') == '0'

        # NULL historical sources coexist; source identity survives logical deletion.
        sql("INSERT INTO points_task_basics(task_name,user_id) VALUES ('ordinary',1),('ordinary2',1); "
            "INSERT INTO points_task_basics(task_name,user_id,source_plan_id,source_plan_date,deleted) "
            "VALUES ('generated',1,2,'2026-10-10',1);")
        duplicate_task = ("INSERT INTO points_task_basics(task_name,user_id,source_plan_id,source_plan_date) "
                          "VALUES ('duplicate',1,2,'2026-10-10');")
        sql(duplicate_task, expected_error='1062')
        sql("INSERT INTO points_task_bookkeeping(user_id,task_id,template_snapshot) VALUES (1,1,'{}'),(1,2,'{}');")
        sql("INSERT INTO points_task_bookkeeping(user_id,task_id,template_snapshot) VALUES (1,1,'{}');", expected_error='1062')
        sql('UPDATE points_task_bookkeeping SET bookkeeping_record_id=8001,recorded_at=NOW() WHERE task_id=1;')
        sql('UPDATE points_task_bookkeeping SET bookkeeping_record_id=8001 WHERE task_id=2;', expected_error='1062')
        sql("UPDATE points_task_bookkeeping SET template_snapshot='not json' WHERE task_id=2;", expected_error='3140')
        sql('DELETE FROM points_task_basics WHERE id=1; DELETE FROM points_task_plan WHERE id=1;')
        assert sql('SELECT bookkeeping_record_id FROM points_task_bookkeeping WHERE task_id=1;') == '8001'

        # A transaction failure leaves completion, reward and association unchanged.
        sql('START TRANSACTION; UPDATE points_task_basics SET task_status=1,completion_reward_points=5 WHERE id=2; '
            'UPDATE points_task_bookkeeping SET bookkeeping_record_id=8002 WHERE task_id=2; ROLLBACK;')
        assert sql('SELECT task_status,completion_reward_points FROM points_task_basics WHERE id=2;') == '0\t0'
        assert sql('SELECT bookkeeping_record_id IS NULL FROM points_task_bookkeeping WHERE task_id=2;') == '1'

        # Concurrent workers have one winner for the same plan period.
        concurrent_insert = ("INSERT INTO points_task_basics(task_name,user_id,source_plan_id,source_plan_date) "
                             "VALUES ('concurrent',1,3,'2026-10-11');")
        def insert_once():
            return subprocess.run(command + [database], input=concurrent_insert, text=True, capture_output=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: insert_once(), range(2)))
        assert sum(result.returncode == 0 for result in results) == 1
        assert all(result.returncode == 0 or '1062' in result.stderr for result in results)
        assert sql('SELECT COUNT(*) FROM points_task_basics WHERE source_plan_id=3;') == '1'

        print('PASS: historical migration, durable uniqueness, JSON, rollback, concurrent generation')
    finally:
        sql(f'DROP DATABASE {database};', global_scope=True)


if __name__ == '__main__':
    main()
