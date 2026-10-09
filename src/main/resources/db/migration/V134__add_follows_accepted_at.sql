-- Records when a follow became accepted, separately from when the request was created, so
-- "followed you in the last 7 days" counts an old request approved today as a recent follower.
ALTER TABLE follows ADD COLUMN accepted_at TIMESTAMPTZ NULL;

UPDATE follows SET accepted_at = created_at WHERE status = 'accepted' AND accepted_at IS NULL;

CREATE OR REPLACE FUNCTION fn_follow_accepted_at()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'accepted'
       AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'accepted') THEN
        NEW.accepted_at := NOW();
    ELSIF NEW.status <> 'accepted' THEN
        NEW.accepted_at := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_follow_accepted_at
    BEFORE INSERT OR UPDATE OF status ON follows
    FOR EACH ROW EXECUTE FUNCTION fn_follow_accepted_at();
