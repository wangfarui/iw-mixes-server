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
    member_source_project_id bigint unsigned null comment '添加成员时使用的CODING项目ID',
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
    managed_statistics tinyint(1) not null default 0 comment '是否使用工作台团队管理令牌统计',
    ignore_team_statistics tinyint(1) not null default 0 comment '是否忽略团队工时与代码统计',
    source_project_id bigint unsigned null comment '添加该成员时验证的CODING项目ID',
    create_time    datetime default current_timestamp not null comment '加入时间',
    update_time    datetime default current_timestamp not null on update current_timestamp comment '资料更新时间',
    primary key (id),
    unique key uk_team_user (team_id, coding_user_id),
    key idx_user_sort (coding_user_id, sort_no, team_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台团队成员';

create table external_zhaogang_workbench_team_token
(
    team_id          bigint unsigned not null comment '工作台团队ID',
    token_ciphertext varchar(2048)    not null comment '管理令牌密文',
    owner_user_id    bigint unsigned not null comment '令牌所属CODING用户ID',
    owner_name       varchar(128)    not null comment '令牌所属CODING用户名称',
    verified_at      datetime        not null comment '最近验证时间（北京时间）',
    primary key (team_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台团队统计管理令牌';

create table external_zhaogang_coding_credential
(
    coding_team_id    bigint unsigned                    not null comment 'CODING团队ID',
    coding_user_id    bigint unsigned                    not null comment 'CODING用户ID',
    token_plaintext   varchar(512)                       not null comment 'CODING PAT明文备份，仅服务端团队工时与团队代码统计使用',
    token_fingerprint varchar(128)                       not null comment 'PAT SHA-256指纹',
    user_name         varchar(128)                       not null comment 'CODING用户名称快照',
    avatar            varchar(512)                       null comment 'CODING用户头像快照',
    last_verified_at  datetime                           not null comment '最近一次令牌校验时间',
    create_time       datetime default current_timestamp not null,
    update_time       datetime default current_timestamp not null on update current_timestamp,
    primary key (coding_team_id, coding_user_id),
    key idx_token_fingerprint (token_fingerprint)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台CODING成员凭证备份';

create table external_zhaogang_agent_token
(
    coding_team_id bigint unsigned not null comment 'CODING团队ID',
    coding_user_id bigint unsigned not null comment 'CODING用户ID',
    user_name      varchar(128)    not null comment '用户名称快照',
    avatar         varchar(512)    null comment '用户头像快照',
    token_hash     char(64)        not null comment 'Agent令牌SHA-256',
    token_hint     varchar(64)     not null comment '令牌前后缀脱敏展示',
    created_at     datetime default current_timestamp not null comment '生成时间',
    last_used_at   datetime        null comment '最近使用时间',
    primary key (coding_team_id, coding_user_id),
    unique key uk_agent_token_hash (token_hash),
    key idx_agent_token_user (coding_user_id)
) engine = InnoDB default charset = utf8mb4 comment = '找钢工作台Agent访问令牌';

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

create table external_zhaogang_ai_config
(
    coding_team_id       bigint unsigned not null comment 'CODING顶层团队ID',
    coding_user_id       bigint unsigned not null comment 'CODING用户ID',
    api_url              varchar(1000)   not null comment 'OpenAI Responses API服务地址，调用时固定补充/v1/responses',
    api_key              varchar(2048)   not null comment '用户AI API Key明文，仅服务端受控读取',
    model                varchar(128)    not null default 'gpt-5.6-terra' comment '模型名称',
    execution_location   varchar(16)     not null comment 'AUTO/SERVER/LOCAL_AGENT',
    create_time          datetime default current_timestamp not null,
    update_time          datetime default current_timestamp not null on update current_timestamp,
    primary key (coding_team_id, coding_user_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台用户AI识别配置';

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
    board_order          bigint default 0                   not null comment '看板内排序值，越小越靠前',
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
    key idx_team_stage_order (team_key, stage, board_order, id),
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
    manually_linked   tinyint(1) default 0                  not null comment '是否为人工事项补绑CODING链接',
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
    prototype_url     varchar(255)                          null comment '工作台需求原型链接，不同步CODING',
    development_team  varchar(128)                          null comment '用户故事开发团队业务值',
    definition_of_done varchar(256)                         null comment '用户故事DoD业务值',
    estimated_hours   decimal(10, 2)                        null comment '子工作项预估工时（小时）',
    task_type         varchar(128)                          null comment '子工作项任务类型业务值',
    priority          varchar(1)                            null comment '子工作项优先级：0低/1中/2高/3紧急',
    coding_recorded_hours decimal(10, 2)                    null comment 'CODING已记录工时（小时）',
    coding_worklogs_loaded tinyint(1) not null default 0 comment '已同步工时明细',
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
    delete_started_at  datetime null comment '删除操作租约',
    coding_worklog_id  bigint unsigned null comment 'CODING工时日志ID',
    source             varchar(16) not null default 'WORKBENCH' comment 'WORKBENCH/CODING',
    coding_missing     tinyint(1) not null default 0 comment '同步确认已不存在或主动删除',
    coding_request_id  varchar(128)                       null comment 'CODING请求ID',
    synced_at          datetime                           null comment '同步到CODING时间',
    creator_user_id    bigint unsigned                    not null comment '登记人CODING用户ID',
    creator_user_name  varchar(128)                       not null comment '登记人名称快照',
    deleted            tinyint(1) default 0               not null comment '是否删除',
    create_time        datetime default current_timestamp not null comment '创建时间',
    update_time        datetime default current_timestamp not null on update current_timestamp comment '更新时间',
    primary key (id),
    key idx_iteration_issue_time (iteration_id, issue_id, registered_at, id),
    unique key uk_issue_coding_worklog (issue_id, coding_worklog_id),
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

-- 找钢工作台代码统计
create table external_zhaogang_code_scan_job
(
    id               bigint unsigned                    not null auto_increment comment '扫描任务ID',
    coding_team_id   bigint unsigned                    not null comment 'CODING顶层团队ID',
    scope_type       varchar(16)                        not null comment '范围：SELF/WORKBENCH_TEAM',
    scope_id         bigint unsigned                    not null comment 'SELF=CODING用户ID；TEAM=工作台团队ID',
    month            char(7)                            not null comment '扫描自然月 YYYY-MM',
    scan_mode        varchar(16)                        not null comment 'FULL/INCREMENTAL/DIFF_BACKFILL',
    status           varchar(16)                        not null comment 'QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED/CANCELLED/INTERRUPTED',
    stage            varchar(16)                        not null comment 'DISCOVERING/IDENTITIES/BRANCHES/COMMITS/DIFFS/AGGREGATING/DONE',
    active_key       varchar(128)                       null comment '活动任务唯一键，完成/失败/取消后置空',
    total_repos      int unsigned default 0             not null comment '仓库总数',
    done_repos       int unsigned default 0             not null comment '已完成仓库数',
    total_branches   int unsigned default 0             not null comment '分支总数',
    done_branches    int unsigned default 0             not null comment '已完成分支数',
    total_commits    int unsigned default 0             not null comment '去重后提交总数',
    done_commits     int unsigned default 0             not null comment '已处理提交数',
    total_diffs      int unsigned default 0             not null comment '待取 diff 的已归属提交数',
    done_diffs       int unsigned default 0             not null comment '已处理 diff 数',
    failed_repos     int unsigned default 0             not null comment '失败仓库数',
    failed_diffs     int unsigned default 0             not null comment '失败/超大 diff 数',
    truncated        tinyint(1)     default 0           not null comment '是否触发保护阈值截断',
    error_code       varchar(64)                        null comment '任务级错误码',
    error_message    varchar(512)                       null comment '可展示的任务级错误',
    requested_by     bigint unsigned                    not null comment '触发人CODING用户ID',
    identity_signature char(64)                         null comment '成员身份集合指纹；变化后重读元数据',
    active_scope_key varchar(128) GENERATED ALWAYS AS
      (CASE WHEN status IN ('QUEUED','RUNNING') THEN concat(coding_team_id, ':', scope_type, ':', scope_id) ELSE NULL END) STORED,
    sync_from date NULL,
    sync_to date NULL,
    sync_team_key varchar(128) NULL,
    sync_team_host varchar(255) NULL,
    requested_mode   varchar(16)                        not null comment 'AUTO/MANUAL',
    started_at       datetime                           null comment '开始执行时间',
    finished_at      datetime                           null comment '结束时间',
    heartbeat_at     datetime default current_timestamp not null comment '工作线程心跳',
    create_time      datetime default current_timestamp not null,
    update_time      datetime default current_timestamp not null on update current_timestamp,
    primary key (id),
    unique key uk_active_key (active_key),
    unique key uk_code_active_scope (active_scope_key),
    key idx_scope_month_time (coding_team_id, scope_type, scope_id, month, create_time),
    key idx_code_sync_scope (coding_team_id, scope_type, scope_id, scan_mode, id),
    key idx_status_heartbeat (status, heartbeat_at)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台代码扫描任务';

create table external_zhaogang_code_scan_repo
(
    id                   bigint unsigned not null auto_increment comment '任务内仓库检查点ID',
    job_id               bigint unsigned not null comment '扫描任务ID',
    project_id           bigint unsigned not null comment 'CODING项目ID',
    project_name         varchar(128)    not null comment 'CODING项目路径/名称',
    project_display_name varchar(128)    not null default '' comment '项目展示名快照，可为空',
    depot_id             bigint unsigned not null comment 'CODING仓库ID',
    depot_name           varchar(128)    not null comment '仓库名称',
    web_url              varchar(512)    not null default '' comment '仓库Web地址，用于拼接提交链接',
    sync_state longtext NULL,
    branch_count         int unsigned default 0 not null comment '本次发现的分支数',
    commit_count         int unsigned default 0 not null comment '本次去重提交数',
    status               varchar(16)     not null comment 'PENDING/RUNNING/COMPLETED/FAILED/SKIPPED',
    error_code           varchar(64)     null comment '仓库级错误码',
    error_message        varchar(512)    null comment '可展示的仓库级错误',
    create_time          datetime default current_timestamp not null,
    update_time          datetime default current_timestamp not null on update current_timestamp,
    primary key (id),
    unique key uk_job_depot (job_id, depot_id),
    key idx_job_status (job_id, status)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台代码扫描仓库检查点';

CREATE TABLE IF NOT EXISTS external_zhaogang_code_checkpoint_part
(
    job_id          BIGINT UNSIGNED NOT NULL,
    depot_id        BIGINT UNSIGNED NOT NULL,
    part_no         SMALLINT UNSIGNED NOT NULL,
    checkpoint_data MEDIUMTEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (job_id, depot_id, part_no),
    CONSTRAINT fk_zg_code_checkpoint_repo FOREIGN KEY (job_id, depot_id)
        REFERENCES external_zhaogang_code_scan_repo (job_id, depot_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

create table external_zhaogang_code_commit
(
    id                   bigint unsigned not null auto_increment comment '提交统计行ID',
    coding_team_id       bigint unsigned not null comment 'CODING顶层团队ID',
    scope_type           varchar(16)     not null comment '范围：SELF/WORKBENCH_TEAM',
    scope_id             bigint unsigned not null comment 'SELF=CODING用户ID；TEAM=工作台团队ID',
    month                char(7)         not null comment '提交所属自然月 YYYY-MM',
    project_id           bigint unsigned not null comment 'CODING项目ID',
    project_name         varchar(128)    not null comment 'CODING项目路径/名称',
    project_display_name varchar(128)    not null default '' comment '项目展示名快照',
    depot_id             bigint unsigned not null comment 'CODING仓库ID',
    depot_name           varchar(128)    not null comment '仓库名称',
    sha                  varchar(64)     not null comment 'Git提交SHA',
    short_sha            varchar(12)     not null comment '短SHA',
    author_name          varchar(128)    not null default '' comment 'Git作者名，仅展示',
    author_email_hash    char(64)        null comment '作者邮箱SHA-256，不存明文邮箱',
    author_name_key      varchar(64)     not null default '' comment '归一化作者名SHA-256，空名不参与匹配',
    commit_at            datetime        not null comment '作者提交时间（Asia/Shanghai）',
    commit_date          date            not null comment '作者提交日期（Asia/Shanghai）',
    message              varchar(1024)   not null default '' comment '提交说明，截断保存，仅纯文本展示',
    branch_names         text            null comment '该提交出现过的分支名JSON数组，最多50个',
    branch_count         int unsigned default 0 not null comment '该提交出现过的分支数',
    branch_truncated     tinyint(1) default 0 not null comment '分支名是否因上限截断',
    additions            bigint unsigned null comment '新增行数；null=未统计',
    deletions            bigint unsigned null comment '删除行数；null=未统计',
    files_changed        int unsigned    null comment '修改文件数；null=未统计',
    file_type_stats      json            null comment '各扩展名新增与删除行数，SQL NULL 表示未统计',
    file_types_checked_at datetime       null comment '历史文件类型最近一次补算时间',
    diff_status          varchar(24)     not null comment 'PENDING/OK/SKIPPED_UNMAPPED/SKIPPED_MERGE/SKIPPED_REPOSITORY/FAILED/TOO_LARGE',
    diff_retry_attempts  tinyint unsigned not null default 0,
    diff_retry_after     datetime         null,
    diff_retry_credential_signature char(64) null,
    diff_retry_error     varchar(32)      null,
    is_merge             tinyint(1) default 0 not null comment '是否合并提交（Parents>1）',
    commit_url           varchar(768)    not null default '' comment 'CODING提交Web地址',
    last_seen_scan_id    bigint unsigned not null default 0 comment '最近一次看到该提交的全量扫描任务ID',
    create_time          datetime default current_timestamp not null,
    update_time          datetime default current_timestamp not null on update current_timestamp,
    primary key (id),
    unique key uk_scope_depot_sha (coding_team_id, scope_type, scope_id, depot_id, sha),
    key idx_code_scope_date (coding_team_id, scope_type, scope_id, commit_date),
    key idx_code_diff_retry (coding_team_id, scope_type, scope_id, diff_status, commit_date),
    key idx_scope_month_date (coding_team_id, scope_type, scope_id, month, commit_date),
    key idx_scope_month_depot (coding_team_id, scope_type, scope_id, month, depot_id),
    key idx_scope_month_email (coding_team_id, scope_type, scope_id, month, author_email_hash),
    key idx_scope_month_name (coding_team_id, scope_type, scope_id, month, author_name_key),
    key idx_scope_seen (coding_team_id, scope_type, scope_id, month, last_seen_scan_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台代码提交统计缓存';

create table external_zhaogang_code_identity
(
    id             bigint unsigned not null auto_increment comment '身份映射ID',
    coding_team_id bigint unsigned not null comment 'CODING顶层团队ID',
    scope_type     varchar(16)     not null comment '范围：SELF/WORKBENCH_TEAM',
    scope_id       bigint unsigned not null comment 'SELF=CODING用户ID；TEAM=工作台团队ID',
    identity_type  varchar(8)      not null comment 'EMAIL/NAME',
    identity_key   char(64)        not null comment '邮箱或作者名归一化后的SHA-256',
    coding_user_id bigint unsigned null comment '归属成员CODING用户ID；忽略时为空',
    ignored        tinyint(1) default 0 not null comment '是否忽略该身份',
    author_name    varchar(128)    not null default '' comment '作者名展示快照',
    source         varchar(16)     not null comment 'DEFAULT/AUTO/MANUAL/SELF/DELETED',
    updated_by     bigint unsigned not null comment '最后操作人CODING用户ID',
    create_time    datetime default current_timestamp not null,
    update_time    datetime default current_timestamp not null on update current_timestamp,
    primary key (id),
    unique key uk_scope_identity (coding_team_id, scope_type, scope_id, identity_type, identity_key),
    key idx_scope_member (coding_team_id, scope_type, scope_id, coding_user_id)
) engine = InnoDB default charset = utf8mb4 comment '找钢工作台代码作者身份映射';


create table if not exists external_zhaogang_workbench_profile
(
    coding_team_id  bigint unsigned not null comment 'CODING顶层团队ID',
    coding_user_id  bigint unsigned not null comment 'CODING用户ID',
    coding_role     varchar(16) null comment '工作台偏好的CODING角色',
    mobile          varchar(11) null comment '企微提醒手机号，同一CODING团队共享',
    role_configured tinyint(1) not null default 0 comment '是否已明确设置角色，含清空',
    last_used_at    datetime null comment '最近使用工作台的时间（北京时间）',
    primary key (coding_team_id, coding_user_id)
) engine = InnoDB default charset = utf8mb4 comment = '找钢工作台用户资料';

-- 找钢工作台共享 Skills
CREATE TABLE IF NOT EXISTS external_zhaogang_skill (
    id CHAR(36) PRIMARY KEY,
    machine_name VARCHAR(64) NOT NULL,
    active_name VARCHAR(64) UNIQUE,
    creator_team BIGINT NOT NULL,
    creator_user BIGINT NOT NULL,
    current_version VARCHAR(32),
    revision BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_version (
    skill_id CHAR(36) NOT NULL,
    version VARCHAR(32) NOT NULL,
    major_no INT NOT NULL,
    minor_no INT NOT NULL,
    patch_no INT NOT NULL,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    notes VARCHAR(2000) NOT NULL,
    package_id CHAR(36) NOT NULL UNIQUE,
    sha256 CHAR(64) NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    purged_at TIMESTAMP NULL,
    PRIMARY KEY (skill_id, version),
    KEY idx_zg_skill_version_purge (deleted_at, purged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_draft (
    id CHAR(36) PRIMARY KEY,
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    skill_id CHAR(36),
    base_revision BIGINT,
    machine_name VARCHAR(64) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    published_skill_id CHAR(36),
    published_version VARCHAR(32),
    KEY idx_zg_skill_draft_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id CHAR(36) NOT NULL,
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    source VARCHAR(12) NOT NULL,
    action VARCHAR(24) NOT NULL,
    previous_version VARCHAR(32),
    current_version VARCHAR(32),
    target_version VARCHAR(32),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_zg_skill_event_history (skill_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_device (
    id CHAR(36) PRIMARY KEY,
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    label VARCHAR(120) NOT NULL,
    actor_name VARCHAR(120) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_zg_skill_device_actor (actor_team, actor_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_device_epoch (
    actor_team BIGINT NOT NULL,
    actor_user BIGINT NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (actor_team, actor_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_skill_pair (
    code CHAR(10) PRIMARY KEY,
    secret_hash CHAR(64) NOT NULL UNIQUE,
    label VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    actor_team BIGINT,
    actor_user BIGINT,
    actor_name VARCHAR(120),
    epoch BIGINT,
    KEY idx_zg_skill_pair_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deletion_revision (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (coding_team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deleted_issue (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    coding_issue_id BIGINT UNSIGNED NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_code BIGINT UNSIGNED NOT NULL,
    first_confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (coding_team_id, coding_issue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Workbench daily worklog group notifications
-- Additive, repeatable migration. No secrets/default enabled notifications.
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_config (
  team_id BIGINT NOT NULL PRIMARY KEY,
  coding_team_id BIGINT NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  webhook_cipher TEXT NULL,
  webhook_hash CHAR(64) NULL,
  summary_member_id BIGINT NULL,
  send_time TIME NOT NULL DEFAULT '17:00:00',
  delivery_revision BIGINT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0,
  last_test_at BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_batch (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  team_id BIGINT NOT NULL,
  coding_team_id BIGINT NOT NULL,
  batch_key VARCHAR(80) NOT NULL,
  kind VARCHAR(12) NOT NULL,
  revision BIGINT NOT NULL,
  webhook_hash CHAR(64) NOT NULL,
  member_ids TEXT NOT NULL,
  snapshot_json LONGTEXT NULL,
  state VARCHAR(16) NOT NULL,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  total_count INT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY uk_team_day (team_id, batch_key),
  KEY idx_state_created (state, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_message (
  batch_id VARCHAR(36) NOT NULL,
  sequence_no INT NOT NULL,
  content TEXT NOT NULL,
  message_type VARCHAR(16) NOT NULL DEFAULT 'markdown',
  mentioned_mobiles VARCHAR(255) NOT NULL DEFAULT '',
  state VARCHAR(16) NOT NULL DEFAULT 'WAITING',
  attempts INT NOT NULL DEFAULT 0,
  next_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (batch_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_notify_channel (
  webhook_hash CHAR(64) NOT NULL PRIMARY KEY,
  active_batch VARCHAR(36) NULL,
  next_at BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Dev Hub persisted worklogs and background synchronization
-- Additive and repeatable. Run before deploying iw-external with worklog mirroring.
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_source (
    source_key CHAR(64) NOT NULL,
    coding_team_id BIGINT NOT NULL,
    coding_user_id BIGINT NOT NULL,
    management_team_id BIGINT NOT NULL DEFAULT 0,
    credential_fingerprint CHAR(64) NOT NULL,
    synced_through BIGINT NOT NULL DEFAULT 0,
    history_before BIGINT NOT NULL DEFAULT 0,
    increment_at BIGINT NOT NULL DEFAULT 0,
    data_version BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    warning VARCHAR(255) NOT NULL DEFAULT '',
    blocked TINYINT NOT NULL DEFAULT 0,
    incomplete TINYINT NOT NULL DEFAULT 0,
    requested_from BIGINT NOT NULL DEFAULT -1,
    incremental TINYINT NOT NULL DEFAULT 1,
    priority_no INT NOT NULL DEFAULT 10,
    rerun_requested TINYINT NOT NULL DEFAULT 0,
    lease_id VARCHAR(36) NOT NULL DEFAULT '',
    lease_until BIGINT NOT NULL DEFAULT 0,
    retry_after BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (source_key),
    KEY idx_worklog_member (coding_team_id, coding_user_id),
    KEY idx_worklog_pending (state, retry_after, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_entry (
    source_key CHAR(64) NOT NULL,
    worklog_id BIGINT NOT NULL,
    coding_team_id BIGINT NOT NULL,
    coding_user_id BIGINT NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_id BIGINT NOT NULL,
    issue_code BIGINT NOT NULL,
    start_at BIGINT NULL,
    created_at BIGINT NULL,
    updated_at BIGINT NULL,
    hours DECIMAL(18,4) NOT NULL,
    working_desc MEDIUMTEXT NOT NULL,
    observed_at BIGINT NOT NULL,
    missing TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (source_key, worklog_id),
    KEY idx_worklog_page (source_key, missing, start_at, updated_at, worklog_id),
    KEY idx_worklog_creation (source_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_issue_snapshot (
    source_key CHAR(64) NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_code BIGINT NOT NULL,
    issue_json MEDIUMTEXT NOT NULL,
    checked_at BIGINT NOT NULL,
    PRIMARY KEY (source_key, project_name, issue_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_sync_scope (
    scope_key CHAR(64) NOT NULL,
    requested_at BIGINT NOT NULL,
    synced_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Confirmed worklog deletions prevent a stale/background mirror from showing a removed entry again.
CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deleted_entry (
    coding_team_id BIGINT NOT NULL,
    coding_team_key VARCHAR(64) NOT NULL,
    worklog_id BIGINT NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (coding_team_id, worklog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_worklog_deleted_issue_key (
    coding_team_id BIGINT NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    issue_code BIGINT NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(coding_team_id,project_name,issue_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Dev Hub statistics. MySQL >= 8.0.29 (CREATE TRIGGER IF NOT EXISTS).
-- Apply after the current Dev Hub mirror, team, calendar and code migrations.
-- Stop writers until all triggers exist. No source rows are modified by this migration.
-- Preflight: SELECT CURRENT_USER(), @@GLOBAL.log_bin, @@GLOBAL.log_bin_trust_function_creators;
-- With binlog ON and trust OFF, use a DBA account allowed to create triggers under that policy (ERROR 1419 otherwise).
-- DDL commits individually. After an interruption, verify the objects and rerun without skipping triggers.
CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_revision (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    stat_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no BIGINT UNSIGNED NOT NULL DEFAULT 0,
    visibility_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    code_visibility_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (coding_team_id, stat_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_month (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    stat_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    generation CHAR(36) NOT NULL,
    valid_until BIGINT NOT NULL,
    worklog_synced_at BIGINT,
    code_synced_at BIGINT,
    PRIMARY KEY (coding_team_id, stat_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_day (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    coding_user_id BIGINT UNSIGNED NOT NULL,
    stat_date DATE NOT NULL,
    worklog_hours DECIMAL(24,4) NOT NULL,
    eligible_workday TINYINT(1) NOT NULL,
    overtime_hours DECIMAL(24,4) NOT NULL,
    overtime_days TINYINT UNSIGNED NOT NULL,
    commit_count BIGINT UNSIGNED NOT NULL,
    additions BIGINT UNSIGNED NOT NULL,
    deletions BIGINT UNSIGNED NOT NULL,
    worklog_reason VARCHAR(32),
    commit_reason VARCHAR(32),
    lines_reason VARCHAR(32),
    PRIMARY KEY (coding_team_id, stat_date, coding_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_refresh_task (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    range_key VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) NOT NULL,
    state VARCHAR(12) NOT NULL,
    priority_no TINYINT UNSIGNED NOT NULL,
    attempt INT UNSIGNED NOT NULL DEFAULT 0,
    lease_id CHAR(36),
    lease_until BIGINT NOT NULL DEFAULT 0,
    next_retry_at BIGINT NOT NULL,
    requested_at BIGINT NOT NULL,
    requested_visibility BIGINT UNSIGNED NOT NULL DEFAULT 0,
    requested_source_version CHAR(64) NOT NULL,
    completed_at BIGINT NOT NULL DEFAULT 0,
    safe_error VARCHAR(128),
    PRIMARY KEY (coding_team_id, range_key),
    KEY idx_statistics_dispatch (state, priority_no, next_retry_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS external_zhaogang_statistics_report (
    coding_team_id BIGINT UNSIGNED NOT NULL,
    range_key VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    visibility_version BIGINT UNSIGNED NOT NULL,
    source_version CHAR(64) NOT NULL,
    policy_key VARCHAR(64) NOT NULL,
    generated_at BIGINT NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    PRIMARY KEY (coding_team_id, range_key),
    KEY idx_statistics_report_age (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The worker pages by depot and SHA across current team scopes within each month.
SET @zg_stats_add_index = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'external_zhaogang_code_commit'
      AND index_name = 'idx_org_month_depot_sha'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_commit ADD INDEX idx_org_month_depot_sha
    (coding_team_id, scope_type, month, depot_id, sha)');
PREPARE zg_stats_index FROM @zg_stats_add_index;
EXECUTE zg_stats_index;
DEALLOCATE PREPARE zg_stats_index;

-- Verify duplicate SHA dates across the organization without scanning other commits for each row.
SET @zg_stats_add_sha_lookup = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'external_zhaogang_code_commit'
      AND index_name = 'idx_org_depot_sha'
), 'DO 0', 'ALTER TABLE external_zhaogang_code_commit ADD INDEX idx_org_depot_sha
    (coding_team_id, scope_type, depot_id, sha, scope_id, month)');
PREPARE zg_stats_sha_lookup FROM @zg_stats_add_sha_lookup;
EXECUTE zg_stats_sha_lookup;
DEALLOCATE PREPARE zg_stats_sha_lookup;

DELIMITER $$

CREATE TRIGGER IF NOT EXISTS zg_stats_team_u AFTER UPDATE ON external_zhaogang_workbench_team
FOR EACH ROW
BEGIN
    IF (NOT (NEW.coding_team_id <=> OLD.coding_team_id) OR NOT (NEW.coding_team_key <=> OLD.coding_team_key) OR NOT (NEW.coding_team_host <=> OLD.coding_team_host)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
        IF NEW.coding_team_id <> OLD.coding_team_id THEN
            INSERT INTO external_zhaogang_statistics_revision
                (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
            SELECT OLD.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (OLD.coding_team_id) IS NOT NULL
            ON DUPLICATE KEY UPDATE version_no = version_no + 1,
                visibility_version = visibility_version + VALUES(visibility_version),
                code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
        END IF;
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_team_d AFTER DELETE ON external_zhaogang_workbench_team
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_i AFTER INSERT ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_u AFTER UPDATE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    IF (NOT (NEW.coding_user_id <=> OLD.coding_user_id) OR NOT (NEW.user_name <=> OLD.user_name) OR NOT (NEW.avatar <=> OLD.avatar) OR NOT (NEW.managed_statistics <=> OLD.managed_statistics) OR NOT (NEW.ignore_team_statistics <=> OLD.ignore_team_statistics) OR NOT (NEW.source_project_id <=> OLD.source_project_id)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_d AFTER DELETE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_code_i AFTER INSERT ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 0, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_code_u AFTER UPDATE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    IF NOT (NEW.managed_statistics <=> OLD.managed_statistics)
        OR NOT (NEW.ignore_team_statistics <=> OLD.ignore_team_statistics)
        OR NOT (NEW.source_project_id <=> OLD.source_project_id)
        OR NOT (NEW.coding_user_id <=> OLD.coding_user_id) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 0, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_member_code_d AFTER DELETE ON external_zhaogang_workbench_team_member
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id), '*', 1, 0, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_manager_i AFTER INSERT ON external_zhaogang_workbench_team_token
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_manager_u AFTER UPDATE ON external_zhaogang_workbench_team_token
FOR EACH ROW
BEGIN
    IF (NOT (NEW.token_ciphertext <=> OLD.token_ciphertext) OR NOT (NEW.owner_user_id <=> OLD.owner_user_id)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id), '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = NEW.team_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_manager_d AFTER DELETE ON external_zhaogang_workbench_team_token
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id), '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE ((SELECT coding_team_id FROM external_zhaogang_workbench_team WHERE id = OLD.team_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_credential_i AFTER INSERT ON external_zhaogang_coding_credential
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_credential_u AFTER UPDATE ON external_zhaogang_coding_credential
FOR EACH ROW
BEGIN
    IF (NOT (NEW.token_fingerprint <=> OLD.token_fingerprint)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_credential_d AFTER DELETE ON external_zhaogang_coding_credential
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_role_i AFTER INSERT ON external_zhaogang_workbench_profile
FOR EACH ROW
BEGIN
    IF NEW.coding_role IS NOT NULL THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_role_d AFTER DELETE ON external_zhaogang_workbench_profile
FOR EACH ROW
BEGIN
    IF OLD.coding_role IS NOT NULL THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_role_u AFTER UPDATE ON external_zhaogang_workbench_profile
FOR EACH ROW
BEGIN
    IF (NOT (NEW.coding_role <=> OLD.coding_role)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_u AFTER UPDATE ON external_zhaogang_work_calendar
FOR EACH ROW
BEGIN
    IF (NOT (NEW.version_no <=> OLD.version_no)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_d AFTER DELETE ON external_zhaogang_work_calendar
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_day_i AFTER INSERT ON external_zhaogang_work_calendar_day
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_day_u AFTER UPDATE ON external_zhaogang_work_calendar_day
FOR EACH ROW
BEGIN
    IF (NOT (NEW.work_date <=> OLD.work_date) OR NOT (NEW.day_type <=> OLD.day_type)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT (SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = NEW.calendar_id)) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_calendar_day_d AFTER DELETE ON external_zhaogang_work_calendar_day
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT (SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = OLD.calendar_id), '*', 1, 1, 0 WHERE ((SELECT coding_team_id FROM external_zhaogang_work_calendar WHERE id = OLD.calendar_id)) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_leave_i AFTER INSERT ON external_zhaogang_work_calendar_leave
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_leave_u AFTER UPDATE ON external_zhaogang_work_calendar_leave
FOR EACH ROW
BEGIN
    IF (NOT (NEW.leave_date <=> OLD.leave_date) OR NOT (NEW.coding_user_id <=> OLD.coding_user_id)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_leave_d AFTER DELETE ON external_zhaogang_work_calendar_leave
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_identity_i AFTER INSERT ON external_zhaogang_code_identity
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'SELF' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_identity_u AFTER UPDATE ON external_zhaogang_code_identity
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'SELF' AND (NOT (NEW.identity_type <=> OLD.identity_type) OR NOT (NEW.identity_key <=> OLD.identity_key) OR NOT (NEW.coding_user_id <=> OLD.coding_user_id) OR NOT (NEW.ignored <=> OLD.ignored) OR NOT (NEW.source <=> OLD.source)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_identity_d AFTER DELETE ON external_zhaogang_code_identity
FOR EACH ROW
BEGIN
    IF OLD.scope_type = 'SELF' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_worklog_tombstone_i AFTER INSERT ON external_zhaogang_worklog_deleted_entry
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_worklog_tombstone_u AFTER UPDATE ON external_zhaogang_worklog_deleted_entry
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_tombstone_i AFTER INSERT ON external_zhaogang_worklog_deleted_issue
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_tombstone_u AFTER UPDATE ON external_zhaogang_worklog_deleted_issue
FOR EACH ROW
BEGIN
    IF (NOT (NEW.project_name <=> OLD.project_name) OR NOT (NEW.issue_code <=> OLD.issue_code)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_key_tombstone_i AFTER INSERT ON external_zhaogang_worklog_deleted_issue_key
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_issue_key_tombstone_u AFTER UPDATE ON external_zhaogang_worklog_deleted_issue_key
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_source_i AFTER INSERT ON external_zhaogang_worklog_source
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT NEW.coding_team_id, '*', 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_source_d AFTER DELETE ON external_zhaogang_worklog_source
FOR EACH ROW
BEGIN
    INSERT INTO external_zhaogang_statistics_revision
        (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
    SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
    ON DUPLICATE KEY UPDATE version_no = version_no + 1,
        visibility_version = visibility_version + VALUES(visibility_version),
        code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_source_u AFTER UPDATE ON external_zhaogang_worklog_source
FOR EACH ROW
BEGIN
    IF NEW.blocked <> OLD.blocked OR NEW.credential_fingerprint <> OLD.credential_fingerprint
        OR NEW.management_team_id <> OLD.management_team_id THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF NEW.increment_at <> OLD.increment_at OR NEW.history_before <> OLD.history_before
        OR NEW.incomplete <> OLD.incomplete OR (NEW.state = 'FAILED') <> (OLD.state = 'FAILED')
        OR (NEW.synced_through = 0) <> (OLD.synced_through = 0) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_entry_i AFTER INSERT ON external_zhaogang_worklog_entry
FOR EACH ROW
BEGIN
    IF NEW.start_at IS NOT NULL AND NEW.missing = 0 THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, DATE_FORMAT(DATE_ADD('1970-01-01', INTERVAL FLOOR((NEW.start_at + 28800000) / 86400000) DAY), '%Y-%m'), 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_entry_u AFTER UPDATE ON external_zhaogang_worklog_entry
FOR EACH ROW
BEGIN
    IF OLD.missing = 0 AND (NEW.missing <> 0 OR NOT (OLD.start_at <=> NEW.start_at)
        OR NOT (OLD.hours <=> NEW.hours) OR NOT (OLD.issue_id <=> NEW.issue_id)
        OR NOT (OLD.project_name <=> NEW.project_name) OR OLD.issue_code <> NEW.issue_code) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF OLD.missing <> 0 AND NEW.missing = 0 AND NEW.start_at IS NOT NULL THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, DATE_FORMAT(DATE_ADD('1970-01-01', INTERVAL FLOOR((NEW.start_at + 28800000) / 86400000) DAY), '%Y-%m'), 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_entry_d AFTER DELETE ON external_zhaogang_worklog_entry
FOR EACH ROW
BEGIN
    IF OLD.missing = 0 THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_commit_i AFTER INSERT ON external_zhaogang_code_commit
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'WORKBENCH_TEAM' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, NEW.month, 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_commit_u AFTER UPDATE ON external_zhaogang_code_commit
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'WORKBENCH_TEAM' AND (NOT (NEW.commit_at <=> OLD.commit_at)
        OR NOT (NEW.author_email_hash <=> OLD.author_email_hash)
        OR NOT (NEW.author_name_key <=> OLD.author_name_key) OR NEW.is_merge <> OLD.is_merge
        OR NEW.depot_name <> OLD.depot_name OR (NEW.diff_status = 'DIFF_PERMISSION_DENIED'
        AND OLD.diff_status <> NEW.diff_status)) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, 1, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    ELSEIF NEW.scope_type = 'WORKBENCH_TEAM' AND (NOT (NEW.additions <=> OLD.additions)
        OR NOT (NEW.deletions <=> OLD.deletions) OR NEW.diff_status <> OLD.diff_status) THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, NEW.month, 1, 0, 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_commit_d AFTER DELETE ON external_zhaogang_code_commit
FOR EACH ROW
BEGIN
    IF OLD.scope_type = 'WORKBENCH_TEAM' THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT OLD.coding_team_id, '*', 1, 1, 0 WHERE (OLD.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS zg_stats_scan_u AFTER UPDATE ON external_zhaogang_code_scan_job
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'WORKBENCH_TEAM' AND NEW.status <> OLD.status
        AND NEW.scan_mode <> 'DIFF_RETRY' AND NEW.status NOT IN ('RUNNING', 'QUEUED') THEN
        INSERT INTO external_zhaogang_statistics_revision
            (coding_team_id, stat_month, version_no, visibility_version, code_visibility_ms)
        SELECT NEW.coding_team_id, '*', 1, IF(NEW.failed_repos > 0 OR NEW.status = 'FAILED', 1, 0), 0 WHERE (NEW.coding_team_id) IS NOT NULL
        ON DUPLICATE KEY UPDATE version_no = version_no + 1,
            visibility_version = visibility_version + VALUES(visibility_version),
            code_visibility_ms = GREATEST(code_visibility_ms, VALUES(code_visibility_ms));
    END IF;
END$$

DELIMITER ;
