-- Existing installations only. Preserve all URLs and allow name-only bookmarks.
ALTER TABLE life_bookmark MODIFY COLUMN url VARCHAR(2048) NULL;
