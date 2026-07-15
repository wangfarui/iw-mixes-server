-- auth_user 登录标识与账号安全增量迁移脚本（MySQL 8.x）
-- 执行前应停止旧版本 iw-core，避免旧实体继续访问本脚本删除的字段。

-- 旧 email_address 为 NOT NULL，必须先放开约束才能把空字符串清理为 NULL。
alter table auth_user
    modify column password varchar(255) null comment '密码',
    modify column phone_number char(11) null default null comment '电话号码',
    modify column email_address varchar(64) null default null comment '邮箱地址';

update auth_user
set phone_number = null
where phone_number is not null
  and trim(phone_number) = '';

update auth_user
set email_address = null
where email_address is not null
  and trim(email_address) = '';

-- 本条 ALTER TABLE 由 MySQL 原子执行。若有效数据存在重复值，唯一索引创建失败且本条DDL整体回滚。
alter table auth_user
    drop column account_non_expired,
    drop column account_non_locked,
    drop column credentials_non_expired,
    add column active_username varchar(64)
        generated always as (case when deleted = 0 then username else null end) virtual,
    add column active_phone_number char(11)
        generated always as (case when deleted = 0 then phone_number else null end) virtual,
    add column active_email_address varchar(64)
        generated always as (case when deleted = 0 then email_address else null end) virtual,
    add unique key uk_auth_user_active_username (active_username),
    add unique key uk_auth_user_active_phone_number (active_phone_number),
    add unique key uk_auth_user_active_email_address (active_email_address);
