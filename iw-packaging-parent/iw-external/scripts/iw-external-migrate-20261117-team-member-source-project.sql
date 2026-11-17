-- 记住团队的添加成员来源，并保留每位新增成员的来源项目。
set @team_source_sql = if(
    exists(select 1 from information_schema.columns where table_schema = database()
           and table_name = 'external_zhaogang_workbench_team' and column_name = 'member_source_project_id'),
    'select 1',
    'alter table external_zhaogang_workbench_team add column member_source_project_id bigint unsigned null comment ''添加成员时使用的CODING项目ID'''
);
prepare team_source_stmt from @team_source_sql;
execute team_source_stmt;
deallocate prepare team_source_stmt;

set @member_source_sql = if(
    exists(select 1 from information_schema.columns where table_schema = database()
           and table_name = 'external_zhaogang_workbench_team_member' and column_name = 'source_project_id'),
    'select 1',
    'alter table external_zhaogang_workbench_team_member add column source_project_id bigint unsigned null comment ''添加该成员时验证的CODING项目ID'''
);
prepare member_source_stmt from @member_source_sql;
execute member_source_stmt;
deallocate prepare member_source_stmt;
