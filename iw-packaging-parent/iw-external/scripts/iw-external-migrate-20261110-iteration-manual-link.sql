-- 已有工作台数据库先执行此迁移，再部署包含人工事项补链的 iw-external。
ALTER TABLE external_zhaogang_iteration_issue
    ADD COLUMN manually_linked tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否为人工事项补绑CODING链接' AFTER coding_url;
