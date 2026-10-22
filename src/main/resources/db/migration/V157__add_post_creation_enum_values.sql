-- Adds the enum values the post creation features need.
--
-- The three place actions audit the administrative places registry. The place report type lets a
-- person report a place name, which is user-written text shown on other people's posts. The five
-- notification types cover being tagged, a tag waiting for approval, a collaboration invitation,
-- an accepted invitation, and a scheduled post that went back to drafts.
--
-- This file contains nothing but ALTER TYPE statements. PostgreSQL does not make a new enum value
-- usable until the adding transaction has committed, so the config table rows that name these
-- values live in V158.

ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'ban_place';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'unban_place';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'delete_place';
ALTER TYPE report_type ADD VALUE IF NOT EXISTS 'place';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'post_tag';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'post_tag_request';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'post_collaboration_invite';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'post_collaboration_accepted';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'scheduled_post_failed';
