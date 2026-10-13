ALTER TABLE refresh_tokens
    ADD COLUMN rotation_request_hash VARCHAR(64),
    ADD COLUMN rotated_to_id UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL;

CREATE INDEX idx_refresh_tokens_rotated_to
    ON refresh_tokens(rotated_to_id) WHERE rotated_to_id IS NOT NULL;

COMMENT ON COLUMN refresh_tokens.rotation_request_hash IS
    'SHA-256 of the optional request key for a bounded exact retry; never a raw token';
COMMENT ON COLUMN refresh_tokens.rotated_to_id IS
    'Issued child of this consumed token; recovery requires an active child and matching request key';
