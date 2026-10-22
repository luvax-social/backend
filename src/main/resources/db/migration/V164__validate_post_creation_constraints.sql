-- Validates the constraints V151 and V152 added NOT VALID.
--
-- VALIDATE CONSTRAINT scans the table under a lock that does not block reads or writes, so adding
-- the constraints and checking the old rows are two steps instead of one long lock.

ALTER TABLE posts VALIDATE CONSTRAINT chk_posts_scheduled_at;
ALTER TABLE posts VALIDATE CONSTRAINT chk_posts_published_at;
ALTER TABLE post_media VALIDATE CONSTRAINT chk_post_media_alt_text_length;
