-- New private tables only. Run 00-preflight.sql first and stop if any target exists.
CREATE TABLE life_wish (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT NOT NULL,
 title VARCHAR(80) NOT NULL, remark VARCHAR(1000) NOT NULL DEFAULT '',
 estimated_amount DECIMAL(10,2) NULL, target_date DATE NULL,
 priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL', status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 reference_url VARCHAR(2048) NULL, fulfilled_at DATETIME NULL,
 revision INT NOT NULL DEFAULT 1, client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_hash CHAR(64) CHARACTER SET ascii NOT NULL, deleted TINYINT(1) NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_wish_request(user_id,client_request_id), KEY idx_wish_owner(user_id,deleted,status),
 CONSTRAINT chk_wish_amount CHECK(estimated_amount IS NULL OR estimated_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE life_important_date (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT NOT NULL,
 title VARCHAR(80) NOT NULL, remark VARCHAR(1000) NOT NULL DEFAULT '',
 date_type VARCHAR(16) NOT NULL, calendar_type VARCHAR(8) NOT NULL,
 event_year INT NOT NULL, event_month INT NOT NULL, event_day INT NOT NULL,
 leap_month TINYINT(1) NOT NULL DEFAULT 0, repeat_type VARCHAR(8) NOT NULL, remind_days INT NULL,
 revision INT NOT NULL DEFAULT 1, client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_hash CHAR(64) CHARACTER SET ascii NOT NULL, deleted TINYINT(1) NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_date_request(user_id,client_request_id), KEY idx_date_owner(user_id,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE life_bookmark (
 id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT NOT NULL,
 title VARCHAR(80) NOT NULL, remark VARCHAR(1000) NOT NULL DEFAULT '', url VARCHAR(2048) NULL,
 category VARCHAR(16) NOT NULL DEFAULT 'OTHER', pinned TINYINT(1) NOT NULL DEFAULT 0,
 revision INT NOT NULL DEFAULT 1, client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_hash CHAR(64) CHARACTER SET ascii NOT NULL, deleted TINYINT(1) NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_bookmark_request(user_id,client_request_id), KEY idx_bookmark_owner(user_id,deleted,pinned)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
