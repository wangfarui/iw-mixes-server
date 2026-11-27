INSERT INTO external_zhaogang_code_commit
(coding_team_id,scope_type,scope_id,month,project_id,project_name,depot_id,depot_name,sha,short_sha,
 author_name,author_email_hash,author_name_key,commit_at,commit_date,branch_names,branch_count,
 additions,deletions,files_changed,file_type_stats,diff_status)
VALUES
(910001,'SELF',101,'2026-10',1,'fixture-project',91,'fixture-main',REPEAT('a',40),'aaaaaaa','Synthetic A',SHA2('a-fixture',256),SHA2('a-name',256),'2026-10-03 12:00:00','2026-10-03','["main"]',1,12,3,1,'{"java":{"additions":12,"deletions":3}}','OK'),
(910001,'WORKBENCH_TEAM',21,'2026-10',1,'fixture-project',91,'fixture-main',REPEAT('a',40),'aaaaaaa','Synthetic A',SHA2('a-fixture',256),SHA2('a-name',256),'2026-10-03 12:00:00','2026-10-03','["release"]',1,NULL,NULL,NULL,NULL,'PENDING'),
(910001,'WORKBENCH_TEAM',22,'2026-10',1,'fixture-project',91,'fixture-main',REPEAT('a',40),'aaaaaaa','Synthetic B',SHA2('b-fixture',256),SHA2('b-name',256),'2026-10-03 12:00:00','2026-10-03','["main","release"]',2,NULL,NULL,NULL,NULL,'TOO_LARGE'),
(910001,'SELF',101,'2026-10',2,'fixture-other',92,'fixture-other',REPEAT('a',40),'aaaaaaa','Synthetic A',SHA2('a-fixture',256),SHA2('a-name',256),'2026-10-03 12:00:00','2026-10-03','["main"]',1,NULL,NULL,NULL,NULL,'PENDING'),
(910002,'SELF',101,'2026-10',1,'fixture-project',91,'fixture-main',REPEAT('a',40),'aaaaaaa','Synthetic A',SHA2('a-fixture',256),SHA2('a-name',256),'2026-10-03 12:00:00','2026-10-03','["main"]',1,5,1,1,NULL,'OK');
SET @source=SHA2('MEMBER:910001:101',256);
INSERT INTO external_zhaogang_worklog_source
(source_key,coding_team_id,coding_user_id,management_team_id,credential_fingerprint,synced_through,history_before,increment_at,state)
VALUES (@source,910001,101,21,SHA2('fixture-legacy-manager',256),1795000000000,1767196800000,1795000000000,'READY');
INSERT INTO external_zhaogang_worklog_entry
(source_key,worklog_id,coding_team_id,coding_user_id,project_name,issue_id,issue_code,start_at,created_at,updated_at,hours,working_desc,observed_at)
VALUES (@source,99901,910001,101,'fixture-project',10,10,1794800000000,1794810000000,1794820000000,4,'fixture history',1795000000000);
