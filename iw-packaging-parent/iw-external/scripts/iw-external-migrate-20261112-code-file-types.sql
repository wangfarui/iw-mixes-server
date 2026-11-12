-- 在升级 iw-external 之前执行。保留现有提交行数，团队扫描会分批补算历史文件类型。
ALTER TABLE external_zhaogang_code_commit
    ADD COLUMN file_type_stats json NULL COMMENT '各扩展名新增与删除行数，SQL NULL 表示未统计' AFTER files_changed,
    ADD COLUMN file_types_checked_at datetime NULL COMMENT '历史文件类型最近一次补算时间' AFTER file_type_stats;
