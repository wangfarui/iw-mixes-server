-- Apply once to the iw-core database before deploying this version.
-- No historical plan/task inference or backfill. All old plans default to disabled.
ALTER TABLE points_task_plan
    ADD COLUMN bookkeeping_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '完成时自动记账',
    ADD COLUMN bookkeeping_template json NULL COMMENT '结构化记账模板及家庭上下文';

ALTER TABLE points_task_basics
    ADD COLUMN source_plan_id int unsigned NULL COMMENT '来源计划',
    ADD COLUMN source_plan_date date NULL COMMENT '生成前的本期计划日期',
    ADD COLUMN completion_reward_points int NOT NULL DEFAULT 0 COMMENT '本次完成实际发放奖励',
    ADD UNIQUE KEY uk_task_plan_period (source_plan_id, source_plan_date);

-- Keep the task snapshot after deletion. A deleted bill may be replaced on the next completion.
CREATE TABLE points_task_bookkeeping (
    id int unsigned NOT NULL AUTO_INCREMENT,
    user_id int unsigned NOT NULL,
    task_id int unsigned NOT NULL,
    source_plan_id int unsigned NULL,
    source_plan_date date NULL,
    task_name_snapshot varchar(128) NOT NULL DEFAULT '',
    template_snapshot json NOT NULL,
    bookkeeping_record_id int unsigned NULL,
    recorded_at datetime NULL,
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_bookkeeping_task (task_id),
    UNIQUE KEY uk_task_bookkeeping_record (bookkeeping_record_id),
    KEY idx_task_bookkeeping_user (user_id),
    KEY idx_task_bookkeeping_plan_period (source_plan_id, source_plan_date)
) ENGINE=InnoDB COMMENT='任务记账快照及最近账单关联';
