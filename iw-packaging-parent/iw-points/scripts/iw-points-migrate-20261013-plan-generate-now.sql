-- 在 20261010 任务记账迁移之后、新版 iw-core 启动之前执行一次。
-- 旧索引应为 uk_task_plan_period；如果是 uk_source_plan_date，先单独执行：
-- ALTER TABLE points_task_basics RENAME INDEX uk_source_plan_date TO uk_task_plan_period;
ALTER TABLE points_task_basics
    ADD COLUMN source_request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '手动生成请求标识，自动生成为空',
    ADD COLUMN scheduled_plan_date date GENERATED ALWAYS AS (
        CASE WHEN source_request_id IS NULL THEN source_plan_date ELSE NULL END
    ) STORED,
    DROP INDEX uk_task_plan_period,
    ADD UNIQUE KEY uk_task_plan_period (source_plan_id, scheduled_plan_date),
    ADD UNIQUE KEY uk_task_plan_request (source_plan_id, source_request_id);
