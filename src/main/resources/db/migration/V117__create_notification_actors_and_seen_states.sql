-- Adds the actor membership of each notification and the per-user seen watermark.
--
-- notification_actors holds every actor of a notification, one row each. An aggregated
-- notification ("anna, ben and 12 others liked your post") has many; every other notification
-- with an actor has exactly one, so display, block handling and account-status handling take one
-- code path. The composite primary key makes a repeated join a no-op, and an unlike or unfollow
-- deletes exactly the retracted actor. notifications.actor_count is the trigger-maintained count
-- of these rows, per the denormalised counter policy.
CREATE TABLE notification_actors (
    notification_id UUID        NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    actor_id        UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    acted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (notification_id, actor_id)
);

-- Platform notices (category system) have no actor membership: their actor_id is null by design.
INSERT INTO notification_actors (notification_id, actor_id, acted_at)
SELECT id, actor_id, created_at
  FROM notifications
 WHERE actor_id IS NOT NULL AND category <> 'system';

-- Set in one statement before the trigger exists, so the backfill does not issue one counter
-- UPDATE per membership row.
UPDATE notifications n
   SET actor_count = c.cnt
  FROM (SELECT notification_id, count(*) AS cnt
          FROM notification_actors
         GROUP BY notification_id) c
 WHERE c.notification_id = n.id;

CREATE OR REPLACE FUNCTION fn_notification_actor_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE notifications SET actor_count = actor_count + 1 WHERE id = NEW.notification_id;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE notifications SET actor_count = GREATEST(actor_count - 1, 0)
         WHERE id = OLD.notification_id;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notification_actor_count
    AFTER INSERT OR DELETE ON notification_actors
    FOR EACH ROW EXECUTE FUNCTION fn_notification_actor_count();

-- One row per user. seen_* is the newest notification the user has been shown in the feed;
-- previous_* is the watermark that defines the "new" section. previous_* is rotated from seen_*
-- only on the first advance after a session gap, so "new" stays stable across a reload within a
-- session. Both are (activity_at, id) tuples, the same tuple that orders the feed.
CREATE TABLE notification_seen_states (
    user_id              UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    seen_activity_at     TIMESTAMPTZ,
    seen_id              UUID,
    previous_activity_at TIMESTAMPTZ,
    previous_id          UUID,
    advanced_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_notification_seen_pair
        CHECK ((seen_activity_at IS NULL) = (seen_id IS NULL)),
    CONSTRAINT chk_notification_previous_pair
        CHECK ((previous_activity_at IS NULL) = (previous_id IS NULL))
);
