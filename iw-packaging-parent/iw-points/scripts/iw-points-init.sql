## 积分相关表
create table points_total
(
    id             int unsigned auto_increment        not null comment 'id',
    points_balance int                                not null comment '积分余额',
    create_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id        int unsigned                       not null comment '用户id',
    primary key (id),
    unique key udx_user_id (user_id)
) comment '积分合计表';

create table points_records
(
    id               int unsigned auto_increment            not null comment 'id',
    transaction_type tinyint(1)   default 1                 not null comment '积分变动分类(1表示增加, 2表示扣减)',
    points           int                                    not null comment '积分变动数量(可以是正数或负数)',
    source           varchar(64)                            not null comment '积分来源',
    source_type      tinyint(1)   default 0                 not null comment '积分来源分类(0表示无分类)',
    remark           varchar(255) default ''                not null comment '积分变动备注',
    deleted          tinyint(1)   default 0                 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time      datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time      datetime     default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id          int unsigned                           not null comment '用户id',
    primary key (id),
    key idx_user_id (user_id)
) comment '积分记录表';

## 任务相关表
create table points_task_group
(
    id          int unsigned auto_increment comment 'id',
    parent_id   int unsigned default 0                 not null comment '父分组id',
    group_name  varchar(32)  default ''                not null comment '分组名称',
    is_top      tinyint(1)   default 0                 not null comment '是否置顶任务 0-否 1-是',
    sort        int          default 0                 not null comment '排序 0-默认排序',
    deleted     tinyint(1)   default 0                 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime     default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id     int unsigned                           not null comment '用户id',
    primary key (id),
    key idx_parent_id (parent_id)
) comment '任务分组表';

create table points_task_basics
(
    id            int unsigned auto_increment comment 'id',
    parent_id     int unsigned                           null comment '父任务id',
    task_group_id int unsigned default 0                 not null comment '任务分组id 0-无分组(收集箱)',
    task_name     varchar(128) default ''                not null comment '任务名称',
    task_remark   text                                   null comment '任务备注',
    task_status   tinyint(1)   default 0                 not null comment '任务状态 0-未完成 1-已完成 2-已放弃',
    deadline_date date                                   null comment '截止日期(在重复任务中可被理解为开始日期)',
    deadline_time time                                   null comment '截止时间(在重复任务中可被理解为开始时间)',
    priority      tinyint(1)   default 0                 not null comment '优先级(数值越大,优先级越高) 0-无优先级',
    is_top        tinyint(1)   default 0                 not null comment '是否置顶任务 0-否 1-是',
    sort          int          default 0                 not null comment '排序 0-默认排序',
    deleted       tinyint(1)   default 0                 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime     default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id       int unsigned                           not null comment '用户id',
    primary key (id),
    key idx_task_group (task_group_id)
) comment '任务基础表';

create table points_task_relation
(
    id int unsigned auto_increment comment 'id',
    task_id       int unsigned not null comment '任务id',
    reward_points         tinyint   default 0                 not null comment '奖励积分',
    punish_points         tinyint   default 0                 not null comment '处罚积分',
    primary key (id),
    key idx_task_id (task_id)
) comment '任务关联表';

alter table points_task_basics
add column done_time datetime null comment '任务完成时间' after sort;

alter table points_task_relation
add column punish_status tinyint(1) default 0 not null comment '惩罚状态 0-未惩罚 1-已惩罚';

create table points_task_plan (
    id int unsigned auto_increment not null comment 'id',
    task_name varchar(128) NOT NULL DEFAULT '' COMMENT '任务名称',
  	task_remark text COMMENT '任务备注',
    plan_cycle tinyint not null comment '计划周期(1:每日, 2:每周, 3:每月, 4:每年, 5:自定义)',
    plan_date date not null comment '计划日期(开始日期)',
    cycle_days smallint unsigned default 0 not null comment '计划天数',
    next_plan_date date not null comment '下一次计划生成时间',
    remind_days smallint unsigned default 0 not null comment '提前提醒天数',
    deadline_days smallint unsigned default 0 not null comment '截止天数',
    reward_points tinyint NOT NULL DEFAULT 0 COMMENT '奖励积分',
  	punish_points tinyint NOT NULL DEFAULT 0 COMMENT '处罚积分',
    status tinyint default 1 not null comment '状态(0禁用 1启用)',
    deleted tinyint(1) default 0 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id int unsigned default 0 not null comment '用户id',
    primary key (id),
    key idx_user_id (user_id)
) comment '任务计划表';

create table points_task_fixed (
    id int unsigned auto_increment not null comment 'id',
    task_name varchar(128) NOT NULL DEFAULT '' COMMENT '任务名称',
    task_points tinyint NOT NULL DEFAULT 0 COMMENT '任务积分(可以是正数或负数)',
  	task_remark varchar(255) not null default '' COMMENT '任务备注',
    deleted tinyint(1) default 0 not null comment '是否删除(true表示已删除, 默认false表示未删除)',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null comment '更新时间',
    user_id int unsigned default 0 not null comment '用户id',
    primary key (id),
    key idx_user_id (user_id)
) comment '常用任务表';


-- 任务计划完成时自动记账（v1）。在部署新版 iw-core 前执行一次。
-- 历史计划默认关闭，历史任务不推断来源、不补建快照或账单。
-- DDL 会自动提交；执行前备份，勿在生产事务中运行。
ALTER TABLE points_task_plan
    ADD COLUMN bookkeeping_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '完成时自动记账' AFTER status,
    ADD COLUMN bookkeeping_template json NULL COMMENT '服务端校验后的记账模板（含版本及家庭上下文）' AFTER bookkeeping_enabled;

ALTER TABLE points_task_basics
    ADD COLUMN source_plan_id int unsigned NULL COMMENT '来源任务计划id，历史及普通任务为空' AFTER done_time,
    ADD COLUMN source_plan_date date NULL COMMENT '生成时的本期计划日期' AFTER source_plan_id,
    ADD COLUMN completion_reward_points int NOT NULL DEFAULT 0 COMMENT '当前完成实际发放的任务积分' AFTER source_plan_date,
    ADD UNIQUE KEY uk_source_plan_date (source_plan_id, source_plan_date);

-- 不设外键、级联删除或 deleted：保留任务快照；账单删除后允许下次完成时重新记账。
-- uk_source_plan_date 不含 deleted，逻辑删除的任务同样占用该期生成凭据。
CREATE TABLE points_task_bookkeeping
(
    id                    int unsigned AUTO_INCREMENT NOT NULL COMMENT 'id',
    user_id               int unsigned NOT NULL COMMENT '所属用户id',
    task_id               int unsigned NOT NULL COMMENT '任务id（永久唯一）',
    source_plan_id        int unsigned NULL COMMENT '历史来源计划id',
    source_plan_date      date NULL COMMENT '历史本期计划日期',
    task_name_snapshot    varchar(128) NOT NULL DEFAULT '' COMMENT '来源任务名称快照',
    template_snapshot     json NOT NULL COMMENT '独立记账快照（含版本及家庭上下文）',
    bookkeeping_record_id int unsigned NULL COMMENT '最近成功生成的账单id，原账单删除后可替换',
    recorded_at           datetime NULL COMMENT '最近成功记账时间',
    create_time           datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_bookkeeping_record_id (bookkeeping_record_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '任务自动记账快照及最近账单关联';
