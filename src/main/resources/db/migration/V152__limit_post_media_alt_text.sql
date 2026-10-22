-- Limits per-item alt text to 500 characters.
--
-- The column was unconstrained TEXT and nothing wrote to it before this release, so no row is
-- expected to exceed the limit. Any that does is copied into archived_post_media_alt_texts in full
-- before it is truncated, so the original text is never lost. The archive has no foreign key
-- because the post_media row it describes may later be deleted with its post.
--
-- The check is added NOT VALID and validated in V164.

CREATE TABLE archived_post_media_alt_texts (
    post_media_id   UUID            PRIMARY KEY,
    alt_text        TEXT            NOT NULL,
    archived_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

INSERT INTO archived_post_media_alt_texts (post_media_id, alt_text)
SELECT id, alt_text
FROM post_media
WHERE char_length(alt_text) > 500
ON CONFLICT (post_media_id) DO NOTHING;

UPDATE post_media
SET alt_text = left(alt_text, 500)
WHERE char_length(alt_text) > 500;

ALTER TABLE post_media
    ADD CONSTRAINT chk_post_media_alt_text_length
    CHECK (alt_text IS NULL OR char_length(alt_text) <= 500) NOT VALID;
