-- Existing environments: run once before deploying the new iw-external version.
-- Nullable local metadata only; no history backfill or CODING writes.
ALTER TABLE external_zhaogang_iteration_issue
    ADD COLUMN prototype_url varchar(255) NULL COMMENT '工作台需求原型链接，不同步CODING' AFTER description;
