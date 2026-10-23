-- Who may tag an account in a post, and whether tags wait for the account's approval.
--
-- tag_permission mirrors message_permission (V135): everyone, following (only accounts the owner of
-- the setting follows), or nobody. manual_tag_approval is off by default, so nothing changes for an
-- existing account. A tag from an account the setting refuses is rejected; a tag the account must
-- approve stays pending until it decides. Both are decided by the interaction policy, never by the
-- tagging code.
--
-- Every existing row takes the defaults, so this is a catalogue-only change.

CREATE TYPE tag_permission AS ENUM ('everyone', 'following', 'nobody');

ALTER TABLE user_settings ADD COLUMN tag_permission tag_permission NOT NULL DEFAULT 'everyone';
ALTER TABLE user_settings ADD COLUMN manual_tag_approval BOOLEAN NOT NULL DEFAULT FALSE;
