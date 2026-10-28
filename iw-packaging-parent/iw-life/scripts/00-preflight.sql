-- Must return no rows before applying 01-schema.sql. Existing tables require structure comparison; do not drop.
SELECT table_name FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN ('life_wish','life_important_date','life_bookmark');
