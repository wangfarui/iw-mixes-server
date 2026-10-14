-- Execute before deploying the new backend. Safe to rerun; historical wallet balances are unchanged.
SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookkeeping_records' AND COLUMN_NAME = 'expense_user_id');
SET @ddl = IF(@column_exists = 0,
 'ALTER TABLE bookkeeping_records ADD COLUMN expense_user_id INT UNSIGNED NULL COMMENT ''实际付款人'' AFTER user_id', 'SELECT 1');
PREPARE payer_migration FROM @ddl;
EXECUTE payer_migration;
DEALLOCATE PREPARE payer_migration;
UPDATE bookkeeping_records SET expense_user_id = user_id WHERE record_category = 1 AND expense_user_id IS NULL;
SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookkeeping_records' AND INDEX_NAME = 'idx_expense_user_date');
SET @ddl = IF(@index_exists = 0,
 'ALTER TABLE bookkeeping_records ADD INDEX idx_expense_user_date (expense_user_id, record_date)', 'SELECT 1');
PREPARE payer_migration FROM @ddl;
EXECUTE payer_migration;
DEALLOCATE PREPARE payer_migration;
