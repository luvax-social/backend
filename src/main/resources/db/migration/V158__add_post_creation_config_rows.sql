-- Registers display metadata and limits for the values added by V157.
--
-- Separate from V157 because these INSERTs and the report_reason_configs update name the new enum
-- values, which PostgreSQL does not make usable until the ALTER TYPE transaction has committed.
--
-- Banning and deleting a place demand a reason, because each hides a name from every surface and
-- writes an audit row. Unbanning does not, the same shape unban_hashtag follows. A ban and an unban
-- undo each other; deleting is reversible too (unban_place brings a deleted place back), but it is
-- flagged not reversible so the panel asks for confirmation the way it does for hashtags.
--
-- The tag types follow the recipient's mentions preference, so they stay user-toggleable. The
-- collaboration invitation and the scheduled failure are not: an invitation asks a question that
-- someone who switched it off would never see, and a failed schedule is the only way the owner
-- learns a post did not go out.

INSERT INTO moderation_action_configs (action_key, display_name, requires_reason, is_reversible, is_enabled) VALUES
    ('ban_place',    'Ban Place',    TRUE,  TRUE,  TRUE),
    ('unban_place',  'Unban Place',  FALSE, TRUE,  TRUE),
    ('delete_place', 'Delete Place', TRUE,  FALSE, TRUE)
ON CONFLICT (action_key) DO NOTHING;

INSERT INTO notification_type_configs (type_key, display_name, template_key, is_user_toggleable, is_enabled) VALUES
    ('post_tag',                   'Tagged in Post',          'post_tag',                   TRUE,  TRUE),
    ('post_tag_request',           'Tag Needs Approval',      'post_tag_request',           TRUE,  TRUE),
    ('post_collaboration_invite',  'Collaboration Invite',    'post_collaboration_invite',  FALSE, TRUE),
    ('post_collaboration_accepted','Collaboration Accepted',  'post_collaboration_accepted',TRUE,  TRUE),
    ('scheduled_post_failed',      'Scheduled Post Failed',   'scheduled_post_failed',      FALSE, TRUE)
ON CONFLICT (type_key) DO NOTHING;

-- The reasons that already list their report types gain place. The reasons with an empty array
-- apply to every report type, so they already cover it.
UPDATE report_reason_configs
SET applies_to = array_append(applies_to, 'place')
WHERE reason_key IN ('nudity', 'violence', 'hate_speech')
  AND NOT ('place' = ANY (applies_to));

INSERT INTO system_settings (key, value, description) VALUES
    ('max_drafts_and_scheduled_posts', '50', 'Maximum drafts and scheduled posts one account can hold together'),
    ('max_post_tags_per_media',        '20', 'Maximum people tagged on one media item of a post'),
    ('max_post_collaborators',         '3',  'Maximum co-authors invited to one post'),
    ('scheduled_post_horizon_days',    '30', 'How far ahead a post can be scheduled, in days')
ON CONFLICT (key) DO NOTHING;
