CREATE TABLE hidden_words (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    phrase             VARCHAR(64)  NOT NULL,
    normalized_phrase  VARCHAR(256) NOT NULL UNIQUE,
    created_by         UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE TRIGGER trg_hidden_words_updated_at BEFORE UPDATE ON hidden_words
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();

CREATE TABLE user_hidden_words (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phrase             VARCHAR(64)  NOT NULL,
    normalized_phrase  VARCHAR(256) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, normalized_phrase)
);
