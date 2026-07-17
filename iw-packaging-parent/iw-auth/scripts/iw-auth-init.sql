create table auth_user
(
    id                      int unsigned                           not null primary key auto_increment comment '用户id',
    username                varchar(64)                            not null comment '用户名',
    password                varchar(255)                           null comment '密码',
    name                    varchar(32)                            not null comment '姓名',
    avatar                  varchar(255) default ''                not null comment '头像',
    enabled                 tinyint(1)   default 1                 not null comment '是否启用(0否, 1是)',
    create_time             datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time             datetime     default CURRENT_TIMESTAMP not null comment '更新时间',
    last_login_time         datetime                               null comment '最后登录时间',
    cancelled_time          datetime                               null comment '账号注销时间'
) comment '用户表';

alter table auth_user
    add column phone_number char(11) null comment '电话号码' after id,
    add index idx_phone_number (phone_number),
    add index idx_username (username);

alter table auth_user
    add column email_address varchar(64) null default null comment '邮箱地址';
alter table auth_user
    add index idx_email_address (email_address);

alter table auth_user
    add column role_type tinyint(4) unsigned not null default 1 comment '角色类型 1普通用户';

alter table auth_user
    add column deleted tinyint(1) not null default 0 comment '是否删除(0未删除,1已删除)' after enabled;

insert into auth_user (username, password, name, role_type)
values ('superadmin', '$2a$10$M4rF5xsU9IRWfc0E1Scc1eEB8RQYYz5tURQFQzL3bWiuI0R/IOLFW', '超级管理员', 20)
;

-- 用户表新增字段：当前家庭组ID
alter table auth_user
    add column family_group_id int not null default 0 comment '当前家庭组ID (0-个人模式)' after role_type;

-- 用户表新增字段：性别
alter table auth_user
    add column gender tinyint unsigned not null default 0 comment '性别(0保密,1男,2女)' after avatar;

-- 仅约束未删除用户的登录标识唯一；逻辑删除数据的生成列为NULL，可以保留重复历史值。
alter table auth_user
    add column active_username varchar(64)
        generated always as (case when deleted = 0 then username else null end) virtual,
    add column active_phone_number char(11)
        generated always as (case when deleted = 0 then phone_number else null end) virtual,
    add column active_email_address varchar(64)
        generated always as (case when deleted = 0 then email_address else null end) virtual,
    add unique key uk_auth_user_active_username (active_username),
    add unique key uk_auth_user_active_phone_number (active_phone_number),
    add unique key uk_auth_user_active_email_address (active_email_address);

-- 家庭组表
create table auth_family_group
(
    id            int unsigned                           not null primary key auto_increment comment '家庭组ID',
    group_name    varchar(32)                            not null comment '家庭组名称',
    group_avatar  varchar(255)                           null comment '家庭组头像',
    group_desc    varchar(255)                           null comment '家庭组描述',
    owner_user_id int unsigned                           not null comment '群主用户ID',
    max_member    int unsigned default 10                not null comment '最大成员数',
    status        tinyint(1)   default 1                 not null comment '状态 (1-启用, 0-禁用)',
    user_id       int unsigned                           not null comment '创建人用户ID',
    create_time   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    deleted       tinyint(1)   default 0                 not null comment '逻辑删除 (0-未删除, 1-已删除)',
    index idx_user_id (user_id)
) comment '家庭组表';

-- 家庭成员表
create table auth_family_member
(
    id                int unsigned                         not null primary key auto_increment comment '成员ID',
    group_id          int unsigned                         not null comment '家庭组ID',
    user_id           int unsigned                         not null comment '用户ID',
    role              tinyint(1) default 3                 not null comment '角色 (1-群主, 2-家长, 3-成员, 4-儿童)',
    default_shared    tinyint(1) default 0                 not null comment '默认共享开关(0关闭 1开启)',
    query_only_myself tinyint(1) default 0                 not null comment '共享数据查看范围(0家庭共享 1仅自己)',
    status            tinyint(1) default 1                 not null comment '状态 (1-正常, 2-已退出, 3-已移除)',
    join_time         datetime   default CURRENT_TIMESTAMP not null comment '加入时间',
    create_time       datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time       datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    deleted           tinyint(1) default 0                 not null comment '逻辑删除 (0-未删除, 1-已删除)',
    index idx_group_id (group_id)
) comment '家庭成员表';

-- 家庭邀请表
create table auth_family_invite
(
    id              int unsigned                         not null primary key auto_increment comment '邀请ID',
    group_id        int unsigned                         not null comment '家庭组ID',
    invite_code     char(8)                              not null comment '邀请码',
    inviter_user_id int unsigned                         not null comment '邀请人用户ID',
    valid_hours     int unsigned                         not null comment '有效时长(小时)',
    expire_time     datetime                             not null comment '过期时间',
    status          tinyint(1) default 1                 not null comment '状态 (1-待使用, 2-已使用, 4-已过期)',
    create_time     datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time     datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    deleted         tinyint(1) default 0                 not null comment '逻辑删除 (0-未删除, 1-已删除)',
    index idx_invite_code (invite_code),
    index idx_group_id (group_id)
) comment '家庭邀请表';
