#!/usr/bin/env python3
"""Verify lunar migration in a disposable MySQL schema, never in a business schema."""
import argparse
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
    database = 'iw_plan_lunar_verify_' + uuid.uuid4().hex[:12]
    scripts = Path(__file__).resolve().parent

    def sql(statement, global_scope=False, error=None):
        result = subprocess.run(command + ([] if global_scope else [database]),
                                input=statement, text=True, capture_output=True)
        if error:
            assert result.returncode != 0 and error in result.stderr, result.stderr
        else:
            assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    sql(f'CREATE DATABASE {database} CHARACTER SET utf8mb4;', global_scope=True)
    try:
        fresh = (scripts / 'iw-points-init.sql').read_text()
        historical = '\n'.join(line for line in fresh.splitlines()
                               if 'calendar_type ' not in line and 'anchor_date ' not in line)
        sql(historical)
        sql("""INSERT INTO points_task_plan
            (task_name,plan_cycle,plan_date,next_plan_date,status,deleted,user_id,remind_days)
            VALUES ('daily',1,'2026-11-01','2026-10-29',1,0,1,3),
                   ('disabled',3,'2026-11-30','2026-11-30',0,0,2,0),
                   ('deleted',4,'2027-02-28','2027-02-25',1,1,3,3);
            CREATE TABLE plan_before AS SELECT * FROM points_task_plan;""")
        before_columns = sql('SHOW COLUMNS FROM points_task_plan;').splitlines()
        columns = ','.join('`' + row.split('\t')[0] + '`' for row in before_columns)
        before = sql('SELECT ' + columns + ' FROM points_task_plan ORDER BY id;')
        migration = (scripts / 'iw-points-migrate-20261029-task-plan-lunar.sql').read_text()
        sql(migration)
        assert before == sql('SELECT ' + columns + ' FROM points_task_plan ORDER BY id;'), 'Historical columns changed'
        assert sql("SELECT COUNT(*) FROM points_task_plan WHERE calendar_type='SOLAR' AND anchor_date=plan_date;") == '3'
        sql("INSERT INTO points_task_plan(task_name,plan_cycle,plan_date,next_plan_date) VALUES ('legacy client',1,'2026-11-01','2026-11-01');")
        assert sql("SELECT calendar_type,anchor_date IS NULL FROM points_task_plan WHERE task_name='legacy client';") == 'SOLAR\t1'
        sql("INSERT INTO points_task_plan(task_name,plan_cycle,calendar_type,anchor_date,plan_date,next_plan_date) VALUES ('lunar',3,'LUNAR','2023-02-20','2023-03-22','2023-03-19');")
        sql("INSERT INTO points_task_plan(task_name,plan_cycle,calendar_type,plan_date,next_plan_date) VALUES ('null',1,NULL,'2026-11-01','2026-11-01');", error='1048')
        sql(migration, error='1060')
        # Fresh installation receives the same defaults without running the migration again.
        tables = ['points_task_bookkeeping', 'points_task_basics', 'points_task_plan', 'points_task_fixed',
                  'points_task_relation', 'points_task_group', 'points_records', 'points_total', 'plan_before']
        sql('DROP TABLE ' + ','.join(tables) + ';')
        sql(fresh)
        sql("INSERT INTO points_task_plan(task_name,plan_cycle,plan_date,next_plan_date) VALUES ('fresh',1,'2026-11-01','2026-11-01');")
        assert sql('SELECT calendar_type FROM points_task_plan;') == 'SOLAR'
        print('PASS: historical rows unchanged (all original columns), deleted/disabled plans solar, legacy insert default, lunar insert, NOT NULL, rerun fails visibly, fresh schema')
    finally:
        sql(f'DROP DATABASE {database};', global_scope=True)


if __name__ == '__main__':
    main()
