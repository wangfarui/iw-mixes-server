-- 找钢工作台 AI 截图识别与本机 Agent 通用化增量迁移，重复执行安全。
create table if not exists external_zhaogang_ai_config
(
    coding_team_id       bigint unsigned not null,
    coding_user_id       bigint unsigned not null,
    api_url              varchar(1000)   not null comment 'OpenAI Responses API服务地址，调用时固定补充/v1/responses',
    api_key              varchar(2048)   not null,
    model                varchar(128)    not null default 'gpt-5.6-terra',
    execution_location   varchar(16)     not null default 'AUTO',
    create_time          datetime default current_timestamp not null,
    update_time          datetime default current_timestamp not null on update current_timestamp,
    primary key (coding_team_id, coding_user_id)
) engine = InnoDB default charset = utf8mb4;
