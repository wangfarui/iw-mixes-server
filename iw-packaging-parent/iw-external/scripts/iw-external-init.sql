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
