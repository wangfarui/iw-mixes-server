-- 已有环境在部署支持子工作项优先级的后端前执行。
-- 不回填历史事项；关联事项以 CODING 实际优先级为准。
ALTER TABLE external_zhaogang_iteration_issue
    ADD COLUMN priority varchar(1) NULL COMMENT '子工作项优先级：0低/1中/2高/3紧急' AFTER task_type;
