create table external_sms_records
(
    id             int unsigned                         not null auto_increment comment 'id',
    phone_number   char(11)                             not null comment '电话号码',
    sign_name      varchar(32)                          not null comment '签名名称',
    template_code  varchar(16)                          not null comment '模板CODE',
    template_param varchar(64)                          not null default '' comment '模板参数',
    send_status    tinyint(1)                           not null default 0 comment '发送状态(0待发送, 1发送成功, 2发送失败)',
    deleted        tinyint(1) default 0                 not null comment '是否删除(true表示已删除, 默认false表示未删除',
    create_time    datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime   default CURRENT_TIMESTAMP not null comment '更新时间',
    primary key (id),
    key idx_phone_number (phone_number)
) comment '外部SMS短信记录表';

create table external_exchange_rate
(
    id            int unsigned                       not null auto_increment comment 'id',
    from_currency varchar(16)                        not null comment '转换前货币',
    to_currency   varchar(16)                        not null comment '转换后货币',
    exchange_rate decimal(16, 6)                     not null comment '汇率',
    query_date    date                               not null comment '查询日期',
    from_amount   decimal(16, 6)                     not null comment '转换前金额',
    to_amount     decimal(16, 6)                     not null comment '转换后金额',
    create_time   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    primary key (id),
    key idx_query_date (query_date)
) comment '货币汇率表';

create table external_tool_ai_records
(
    id                 int unsigned                         not null auto_increment comment 'id',
    request_id         varchar(64)                          not null comment '请求ID',
    business_type      varchar(64)                          not null comment '业务类型',
    request_body       text                                 null comment '请求消息体',
    system_prompt      text                                 null comment '系统提示词',
    user_prompt        text                                 null comment '用户提示词',
    response_content   text                                 null comment 'AI响应内容',
    model              varchar(64)                          null comment 'AI模型',
    prompt_tokens      int                                  null comment '输入token数',
    completion_tokens  int                                  null comment '输出token数',
    total_tokens       int                                  null comment '总token数',
    status             varchar(32)                          not null comment '状态(SUCCESS/BLOCKED/QUOTA_EXCEEDED/FAILED)',
    fail_reason        varchar(512)                         null comment '失败原因',
    client_ip          varchar(64)                          null comment '客户端IP',
    client_ip_hash     char(64)                             null comment '客户端IP哈希',
    user_agent         varchar(512)                         null comment 'User-Agent',
    quota_total_after  int                                  null comment '调用后全局额度计数',
    quota_type_after   int                                  null comment '调用后业务类型额度计数',
    quota_ip_after     int                                  null comment '调用后IP额度计数',
    deleted            tinyint(1) default 0                 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time        datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time        datetime   default CURRENT_TIMESTAMP not null comment '更新时间',
    primary key (id),
    unique key uk_request_id (request_id),
    key idx_business_type_create_time (business_type, create_time),
    key idx_status_create_time (status, create_time),
    key idx_client_ip_hash_create_time (client_ip_hash, create_time)
) comment '外部工具AI调用记录表';

create table external_tool_usage_daily
(
    id          bigint unsigned                       not null auto_increment comment 'id',
    stat_date   date                                  not null comment '统计日期',
    tool_key    varchar(64)                           not null comment '稳定工具标识',
    usage_count bigint unsigned                       not null default 0 comment '当日使用次数',
    create_time datetime default CURRENT_TIMESTAMP    not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP    not null on update CURRENT_TIMESTAMP comment '更新时间',
    primary key (id),
    unique key uk_stat_date_tool_key (stat_date, tool_key),
    key idx_tool_key_stat_date (tool_key, stat_date)
) comment '外部工具按日使用统计表';

-- 找钢工作台团队
create table external_zhaogang_workbench_team
(
    id                    bigint unsigned                    not null auto_increment comment '工作台团队ID',
    request_id            varchar(64)                        not null comment '创建幂等请求ID',
    name                  varchar(64)                        not null comment '团队名称',
    invite_code           varchar(64)                        not null comment '固定永久邀请码',
    coding_team_id        bigint unsigned                    not null comment 'CODING顶层团队ID',
    coding_team_key       varchar(64)                        not null comment 'CODING团队标识',
    coding_team_host      varchar(255)                       not null comment 'CODING团队地址',
    creator_user_id       bigint unsigned                    not null comment '创建人CODING用户ID，仅审计',
    administrator_user_id bigint unsigned                    not null comment '当前唯一管理员CODING用户ID',
    version_no            int unsigned default 1             not null comment '乐观锁版本',
    create_time           datetime default current_timestamp not null comment '创建时间',
    update_time           datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_request_id (request_id),
    unique key uk_invite_code (invite_code),
    key idx_administrator_update (administrator_user_id, update_time),
    key idx_coding_team_update (coding_team_id, update_time)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台团队';

create table external_zhaogang_workbench_team_member
(
    id             bigint unsigned                    not null auto_increment comment '团队成员关系ID',
    team_id        bigint unsigned                    not null comment '工作台团队ID',
    coding_user_id bigint unsigned                    not null comment 'CODING用户ID',
    user_name      varchar(128)                       not null comment '成员名称快照',
    avatar         varchar(512)                       null comment '成员头像快照',
    sort_no        int unsigned default 0             not null comment '当前用户的团队显示顺序',
    create_time    datetime default current_timestamp not null comment '加入时间',
    update_time    datetime default current_timestamp not null on update current_timestamp comment '资料更新时间',
    primary key (id),
    unique key uk_team_user (team_id, coding_user_id),
    key idx_user_sort (coding_user_id, sort_no, team_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台团队成员';

create table external_zhaogang_coding_credential
(
    coding_team_id    bigint unsigned                    not null comment 'CODING团队ID',
    coding_user_id    bigint unsigned                    not null comment 'CODING用户ID',
    token_plaintext   varchar(512)                       not null comment 'CODING PAT明文备份，仅服务端团队工时查询使用',
    token_fingerprint varchar(128)                       not null comment 'PAT SHA-256指纹',
    user_name         varchar(128)                       not null comment 'CODING用户名称快照',
    avatar            varchar(512)                       null comment 'CODING用户头像快照',
    last_verified_at  datetime                           not null comment '最近一次令牌校验时间',
    create_time       datetime default current_timestamp not null,
    update_time       datetime default current_timestamp not null on update current_timestamp,
    primary key (coding_team_id, coding_user_id),
    key idx_token_fingerprint (token_fingerprint)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台CODING成员凭证备份';

create table external_zhaogang_release_receipt
(
    coding_team_id bigint unsigned not null comment 'CODING顶层团队ID',
    coding_user_id bigint unsigned not null comment 'CODING用户ID',
    release_id varchar(128) not null comment '找钢工作台版本不可变标识',
    read_at datetime not null comment '服务端确认已读时间',
    primary key (coding_team_id, coding_user_id, release_id),
    key idx_release_id (release_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台版本更新已读记录';

create table external_zhaogang_k8s_token
(
    coding_team_id bigint unsigned not null comment 'CODING团队ID',
    coding_user_id bigint unsigned not null comment 'CODING用户ID',
    environment    varchar(16)     not null comment 'K8s环境：test/uat/prd',
    token_plaintext varchar(4096)   not null comment '用户上传的K8s Dashboard Token',
    create_time    datetime default current_timestamp not null,
    update_time    datetime default current_timestamp not null on update current_timestamp,
    primary key (coding_team_id, coding_user_id, environment),
    key idx_user_environment (coding_user_id, environment)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台用户K8s Token';

create table external_zhaogang_work_calendar
(
    id             bigint unsigned                    not null auto_increment comment '工作日历ID',
    coding_team_id bigint unsigned                    not null comment 'CODING顶层团队ID',
    version_no     int unsigned default 0             not null comment '日历版本，用于工时缓存失效',
    create_time    datetime default current_timestamp not null comment '创建时间',
    update_time    datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_coding_team (coding_team_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作日历';

create table external_zhaogang_work_calendar_day
(
    id                bigint unsigned                    not null auto_increment comment '日期覆盖ID',
    calendar_id       bigint unsigned                    not null comment '工作日历ID',
    work_date         date                               not null comment '日期',
    day_type          varchar(16)                        not null comment 'WORKDAY/REST_DAY',
    updater_user_id   bigint unsigned                    not null comment '最后修改人CODING用户ID',
    updater_user_name varchar(128)                       not null comment '最后修改人名称快照',
    create_time       datetime default current_timestamp not null comment '创建时间',
    update_time       datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_calendar_date (calendar_id, work_date),
    key idx_work_date (work_date)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作日历日期覆盖';

create table external_zhaogang_work_calendar_leave
(
    id             bigint unsigned not null auto_increment comment '个人请假记录ID',
    coding_team_id bigint unsigned not null comment 'CODING顶层团队ID',
    coding_user_id bigint unsigned not null comment 'CODING用户ID',
    leave_date     date             not null comment '请假日期',
    create_time    datetime default current_timestamp not null comment '创建时间',
    primary key (id),
    unique key uk_user_date (coding_team_id, coding_user_id, leave_date),
    key idx_leave_date (coding_team_id, leave_date)
) engine = InnoDB default charset = utf8mb4 comment '找钢个人请假日';

-- 找钢工作台迭代
create table external_zhaogang_iteration
(
    id                   bigint unsigned                    not null auto_increment comment '工作台迭代ID',
    request_id           varchar(64)                        not null comment '创建幂等请求ID',
    team_key             varchar(64)                        not null comment 'CODING团队标识',
    name                 varchar(128)                       not null comment '迭代标题',
    version              varchar(64)                        null comment '版本号',
    stage                varchar(32)                        not null comment 'NOT_STARTED/DEVELOPING/TESTING/RELEASED',
    start_date           date                               null comment '开始日期',
    planned_release_date date                               null comment '计划上线日期',
    creator_user_id      bigint unsigned                    not null comment '创建人CODING用户ID',
    creator_user_name    varchar(128)                       not null comment '创建人名称快照',
    creator_avatar       varchar(512)                       null comment '创建人头像快照',
    updater_user_id      bigint unsigned                    not null comment '最后修改人CODING用户ID',
    updater_user_name    varchar(128)                       not null comment '最后修改人名称快照',
    version_no           int unsigned default 1            not null comment '乐观锁版本',
    released_at          datetime                           null comment '人工切换为已上线的时间',
    deleted              tinyint(1) default 0               not null comment '是否删除',
    create_time          datetime default current_timestamp not null comment '创建时间',
    update_time          datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_request_id (request_id),
    key idx_team_stage_update (team_key, stage, update_time),
    key idx_creator_update (creator_user_id, update_time)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台人工迭代';

create table external_zhaogang_iteration_member
(
    id                  bigint unsigned                    not null auto_increment comment '成员关系ID',
    iteration_id        bigint unsigned                    not null comment '工作台迭代ID',
    workbench_team_id   bigint unsigned                    not null comment '工作台团队ID',
    workbench_team_name varchar(64)                        not null comment '工作台团队名称快照',
    coding_user_id      bigint unsigned                    not null comment 'CODING团队成员ID',
    user_name           varchar(128)                       not null comment '成员名称快照',
    avatar              varchar(512)                       null comment '成员头像快照',
    deleted             tinyint(1) default 0               not null comment '是否删除',
    create_time         datetime default current_timestamp not null comment '创建时间',
    update_time         datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_iteration_user (iteration_id, coding_user_id),
    key idx_user_iteration (coding_user_id, iteration_id)
) engine = InnoDB default charset = utf8mb4 comment '工作台迭代成员';

create table external_zhaogang_iteration_member_role
(
    id          bigint unsigned                    not null auto_increment comment '成员角色ID',
    member_id   bigint unsigned                    not null comment '成员关系ID',
    role        varchar(32)                        not null comment 'PRODUCT/BACKEND/FRONTEND/QA',
    deleted     tinyint(1) default 0               not null comment '是否删除',
    create_time datetime default current_timestamp not null comment '创建时间',
    update_time datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_member_role (member_id, role)
) engine = InnoDB default charset = utf8mb4 comment '工作台迭代成员人工角色';

create table external_zhaogang_iteration_issue
(
    id                bigint unsigned                       not null auto_increment comment '迭代事项ID',
    iteration_id      bigint unsigned                       not null comment '工作台迭代ID',
    parent_id         bigint unsigned                       null comment '工作台父事项ID',
    source            varchar(16)                           not null comment 'CODING/WORKBENCH',
    coding_url        varchar(1000)                         null comment 'CODING事项详情链接',
    url_hash          char(64)                              null comment '项目与事项编号SHA-256摘要',
    project_name      varchar(128)                          not null comment 'CODING项目标识',
    issue_id          bigint unsigned                       null comment 'CODING事项ID',
    issue_code        bigint unsigned                       null comment 'CODING事项编号',
    issue_type        varchar(32)                           not null comment 'REQUIREMENT/TASK/USER_STORY/SUB_TASK/DEFECT',
    coding_system_type varchar(32)                          null comment 'CODING原始系统类型',
    coding_issue_type_id bigint unsigned                    null comment 'CODING项目事项类型ID',
    issue_type_name   varchar(64)                           not null comment '事项类型名称快照',
    title             varchar(256)                          not null comment '事项标题快照',
    description       varchar(4000)                         null comment '工作台人工事项描述',
    development_team  varchar(128)                          null comment '用户故事开发团队业务值',
    definition_of_done varchar(256)                         null comment '用户故事DoD业务值',
    estimated_hours   decimal(10, 2)                        null comment '子工作项预估工时（小时）',
    task_type         varchar(128)                          null comment '子工作项任务类型业务值',
    coding_recorded_hours decimal(10, 2)                    null comment 'CODING已记录工时（小时）',
    coding_worklog_count int unsigned                       null comment 'CODING工时记录次数',
    online_bug        tinyint(1)                            null comment '缺陷是否线上Bug',
    bug_priority      varchar(64)                           null comment '缺陷Bug优先级业务值',
    sync_status       varchar(16)                           not null comment 'NOT_REQUIRED/PENDING/SYNCING/SYNCED/FAILED/UNKNOWN',
    sync_message      varchar(500)                          null comment '最近同步结果',
    sync_error_code   varchar(128)                          null comment '最近CODING错误码',
    sync_started_at   datetime                              null comment '最近同步开始时间',
    sync_attempt_count int unsigned default 0               not null comment '同步尝试次数',
    synced_at         datetime                              null comment '同步到CODING时间',
    coding_parent_code bigint unsigned                      null comment 'CODING子工作项实际父编号',
    creator_user_id   bigint unsigned                       not null comment '创建或关联人CODING用户ID',
    creator_user_name varchar(128)                          not null comment '创建或关联人名称快照',
    deleted           tinyint(1) default 0                  not null comment '是否删除',
    create_time       datetime default current_timestamp    not null comment '创建时间',
    update_time       datetime default current_timestamp    not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_iteration_url (iteration_id, url_hash),
    key idx_iteration_parent (iteration_id, parent_id, id),
    key idx_iteration_issue (iteration_id, issue_code),
    key idx_project_issue (project_name, issue_code)
) engine = InnoDB default charset = utf8mb4 comment '工作台迭代事项树';

create table external_zhaogang_iteration_issue_worklog
(
    id                 bigint unsigned                    not null auto_increment comment '工时登记ID',
    iteration_id       bigint unsigned                    not null comment '工作台迭代ID',
    issue_id           bigint unsigned                    not null comment '工作台子工作项ID',
    spend_hours        decimal(10, 2)                     not null comment '使用工时（小时）',
    registered_at      datetime                           not null comment '登记时间，允许历史或未来时间',
    sync_status        varchar(16)                        not null comment 'NOT_REQUIRED/PENDING/SYNCING/SYNCED/FAILED/UNKNOWN',
    sync_message       varchar(500)                       null comment '最近同步结果',
    sync_error_code    varchar(128)                       null comment '最近CODING错误码',
    sync_started_at    datetime                           null comment '最近同步开始时间',
    sync_attempt_count int unsigned default 0             not null comment '同步尝试次数',
    coding_request_id  varchar(128)                       null comment 'CODING请求ID',
    synced_at          datetime                           null comment '同步到CODING时间',
    creator_user_id    bigint unsigned                    not null comment '登记人CODING用户ID',
    creator_user_name  varchar(128)                       not null comment '登记人名称快照',
    deleted            tinyint(1) default 0               not null comment '是否删除',
    create_time        datetime default current_timestamp not null comment '创建时间',
    update_time        datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    key idx_iteration_issue_time (iteration_id, issue_id, registered_at, id),
    key idx_worklog_sync (sync_status, sync_started_at)
) engine = InnoDB default charset = utf8mb4 comment '工作台子工作项工时登记';

create table external_zhaogang_iteration_release_plan
(
    id                    bigint unsigned                    not null auto_increment comment '迭代发布计划ID',
    iteration_id          bigint unsigned                    not null comment '工作台迭代ID',
    coding_project_id     bigint unsigned                    not null comment 'CODING项目ID',
    coding_project_name   varchar(128)                       not null comment 'CODING项目标识快照',
    project_display_name  varchar(128)                       not null comment 'CODING项目显示名快照',
    coding_plan_id        bigint unsigned                    not null comment 'CODING构建计划ID',
    plan_name             varchar(256)                       not null comment 'CODING构建计划名称快照',
    quick_build_supported tinyint(1)                         not null comment '关联时是否支持工作台快捷构建',
    creator_user_id       bigint unsigned                    not null comment '添加人CODING用户ID',
    creator_user_name     varchar(128)                       not null comment '添加人名称快照',
    creator_avatar        varchar(512)                       null comment '添加人头像快照',
    deleted               tinyint(1) default 0               not null comment '是否删除',
    create_time           datetime default current_timestamp not null comment '创建时间',
    update_time           datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    unique key uk_iteration_project_plan (iteration_id, coding_project_id, coding_plan_id),
    key idx_iteration_create (iteration_id, create_time, id)
) engine = InnoDB default charset = utf8mb4 comment '工作台迭代发布构建计划';
