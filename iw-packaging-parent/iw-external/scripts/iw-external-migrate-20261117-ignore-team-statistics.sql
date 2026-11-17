-- 保留现有成员的统计口径，默认参与团队工时与代码统计。
set @ignore_team_statistics_sql = if(
    exists(select 1 from information_schema.columns where table_schema = database()
           and table_name = 'external_zhaogang_workbench_team_member' and column_name = 'ignore_team_statistics'),
    'select 1',
    'alter table external_zhaogang_workbench_team_member add column ignore_team_statistics tinyint(1) not null default 0 comment ''是否忽略团队工时与代码统计'''
);
prepare ignore_team_statistics_stmt from @ignore_team_statistics_sql;
execute ignore_team_statistics_stmt;
deallocate prepare ignore_team_statistics_stmt;
