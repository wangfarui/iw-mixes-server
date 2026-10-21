-- READ ONLY. Run on the explicit target database before the stop-write migration window.
SELECT DATABASE() AS target_database,@@version AS mysql_version,@@port AS server_port;
SHOW CREATE TABLE base_dict;
SHOW CREATE TABLE bookkeeping_membership_subscription;
SELECT dict_type,scope_type,family_group_id,user_id,deleted,dict_status,COUNT(*) AS rows_count
 FROM base_dict WHERE dict_type=4004 GROUP BY dict_type,scope_type,family_group_id,user_id,deleted,dict_status;
SELECT user_id,dict_code,COUNT(*) AS all_versions,GROUP_CONCAT(id ORDER BY id) AS source_ids
 FROM base_dict WHERE dict_type=4004 AND scope_type=0 AND family_group_id=0
 GROUP BY user_id,dict_code HAVING COUNT(*)>1;
SELECT id,user_id,dict_name,CHAR_LENGTH(dict_name) AS codepoints FROM base_dict
 WHERE dict_type=4004 AND (CHAR_LENGTH(dict_name) NOT BETWEEN 1 AND 32 OR dict_name<>TRIM(dict_name));
SELECT user_id,HEX(dict_name) AS binary_name,COUNT(*) AS current_count FROM base_dict
 WHERE dict_type=4004 AND deleted=0 GROUP BY user_id,scope_type,family_group_id,HEX(dict_name) HAVING COUNT(*)>1;
SELECT s.id,s.user_id,s.membership_type,COUNT(d.id) AS possible_sources,
 GROUP_CONCAT(d.id ORDER BY d.id) AS source_ids FROM bookkeeping_membership_subscription s
 LEFT JOIN base_dict d ON d.user_id=s.user_id AND d.dict_type=4004 AND d.scope_type=0
 AND d.family_group_id=0 AND d.dict_code=s.membership_type
 GROUP BY s.id,s.user_id,s.membership_type;
SELECT * FROM base_dict WHERE dict_type=4004 AND user_id=0 ORDER BY sort,id;
