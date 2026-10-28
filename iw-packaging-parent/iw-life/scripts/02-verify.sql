SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE()
AND table_name IN ('life_wish','life_important_date','life_bookmark') ORDER BY table_name;
SELECT 'wish' AS kind,COUNT(*) AS invalid FROM life_wish WHERE user_id IS NULL OR revision<1 OR create_hash=''
UNION ALL SELECT 'date',COUNT(*) FROM life_important_date WHERE user_id IS NULL OR revision<1 OR create_hash=''
UNION ALL SELECT 'bookmark',COUNT(*) FROM life_bookmark WHERE user_id IS NULL OR revision<1 OR create_hash='';
