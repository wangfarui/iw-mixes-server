-- 一次性迁移。先停 iw-core、完成 points_task_plan 整表备份并核对前置条件。
-- 必须已执行 20261013-plan-generate-now.sql。不要与历史 init.sql 一起重复执行。
-- MySQL DDL 隐式提交；中断后先查实际列状态，不要直接重跑。
ALTER TABLE points_task_plan
    ADD COLUMN calendar_type varchar(8) NOT NULL DEFAULT 'SOLAR' COMMENT '历法 SOLAR公历 LUNAR农历' AFTER plan_date,
    ADD COLUMN anchor_date date NULL COMMENT '原始日期，重复规则的固定锚点' AFTER calendar_type;

-- 原始创建日期已无法从历史滚动日期还原，以迁移时当前计划日期作为固定锚点。
-- 不修改 plan_date、next_plan_date、plan_cycle、status、积分或任务数据。
UPDATE points_task_plan SET anchor_date = plan_date WHERE anchor_date IS NULL;
