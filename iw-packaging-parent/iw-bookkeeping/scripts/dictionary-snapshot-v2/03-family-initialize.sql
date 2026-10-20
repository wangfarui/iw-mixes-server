-- Incremental migration; explicit execution authorization required. MySQL 8.0.21+, InnoDB, utf8mb4.
-- Select the single iw-core database explicitly. Stop old writers and scheduled jobs first.
SET NAMES utf8mb4;

-- Only existing active families without a marker. An empty template still creates a marker.
DROP TEMPORARY TABLE IF EXISTS dict_v2_families;
CREATE TEMPORARY TABLE dict_v2_families(family_group_id INT UNSIGNED PRIMARY KEY);
START TRANSACTION;
INSERT INTO dict_v2_families SELECT g.id FROM auth_family_group g
 LEFT JOIN bookkeeping_family_dict_state s ON s.family_group_id=g.id
 WHERE g.deleted=0 AND g.status=1 AND s.family_group_id IS NULL;
INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,scope_type,family_group_id,revision)
 SELECT 0,4002,0,d.dict_name,d.dict_status,d.sort,0,1,g.family_group_id,0
 FROM dict_v2_families g JOIN base_dict d ON d.user_id=0 AND d.scope_type=0 AND d.family_group_id=0
 AND d.dict_type=4002 AND d.deleted=0 AND d.dict_status=1;
-- A conflict rolls back BOTH copies and markers. Do not use INSERT IGNORE.
INSERT INTO bookkeeping_family_dict_state(family_group_id) SELECT family_group_id FROM dict_v2_families;
COMMIT;
SELECT COUNT(*) families_initialized_this_run FROM dict_v2_families;
SELECT s.family_group_id,s.initialized_at,COUNT(d.id) current_category_count
 FROM bookkeeping_family_dict_state s LEFT JOIN base_dict d
 ON d.scope_type=1 AND d.family_group_id=s.family_group_id AND d.dict_type=4002 AND d.deleted=0
 GROUP BY s.family_group_id,s.initialized_at;
