-- The places registry: a named location a post can reference, created by people typing a name.
--
-- There are no coordinates and no map. A place is identified by normalised_name, which folds case,
-- accents and spacing, so "Da Nang", "da nang" and "Da  Nang" with accents are one place. The Java normaliser
-- (TextMatchNormalizer.normalizeName) and fn_normalise_place_name below implement the same
-- contract; PlaceNameNormalisationParityIT pins them to the same vectors, because the data
-- migration in V154 can only call SQL and the application can only call Java.
--
-- The contract: decompose to NFD, drop combining marks, map the stroked d to d, lower-case, keep runs of
-- letters and digits, join them with one space. Unlike the Hidden Words contract it has no
-- leetspeak step, which would merge distinct names such as "B4 Cafe" and "Ba Cafe".
--
-- status follows the hashtag lifecycle. deleted is a state, never a row removal, so the posts that
-- used a place keep their foreign key and nothing cascades. status_by and created_by are
-- ON DELETE SET NULL so removing an account never deletes the place or the decision about it.
-- post_count is maintained only by trg_place_post_count, created in V154 with the post foreign key.

CREATE TYPE place_status AS ENUM ('active', 'banned', 'deleted');

CREATE FUNCTION fn_normalise_place_name(raw TEXT)
RETURNS TEXT AS $$
    SELECT btrim(
        regexp_replace(
            regexp_replace(
                translate(
                    lower(
                        regexp_replace(
                            normalize(raw, NFD),
                            '[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]',
                            '',
                            'g')),
                    chr(272) || chr(273),
                    'dd'),
                '[^[:alnum:]]+',
                ' ',
                'g'),
            '\s+',
            ' ',
            'g'))
$$ LANGUAGE sql IMMUTABLE;

CREATE TABLE places (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(100)    NOT NULL,
    normalized_name     VARCHAR(100)    NOT NULL,
    status              place_status    NOT NULL DEFAULT 'active',
    status_note         TEXT,
    status_at           TIMESTAMPTZ,
    status_by           UUID            REFERENCES users(id) ON DELETE SET NULL,
    created_by          UUID            REFERENCES users(id) ON DELETE SET NULL,
    post_count          INT             NOT NULL DEFAULT 0 CHECK (post_count >= 0),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_places_normalized_name UNIQUE (normalized_name),
    CONSTRAINT chk_places_normalized_name_not_empty CHECK (normalized_name <> '')
);

CREATE TRIGGER trg_places_updated_at
    BEFORE UPDATE ON places
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();
