-- Indexes for the mail campaign tables.
--
-- Separate from V101, and non-transactional, because every index here builds CONCURRENTLY,
-- which cannot run inside a transaction. Same split V62 and V63 use.

-- The scheduler's claim query: due campaigns in scheduled status, oldest first. Partial, because
-- every other status is irrelevant to it and the table is dominated by sent rows over time.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mail_campaigns_due
    ON mail_campaigns (scheduled_at)
    WHERE status = 'scheduled';

-- Recipients of one campaign, which is how both the send loop and the administrative view read.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mail_campaign_recipients_campaign
    ON mail_campaign_recipients (campaign_id);

-- The unsubscribe endpoint looks a user up by token hash and has no session to narrow it with, so
-- this is the only access path that query has.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_user_settings_unsubscribe_token
    ON user_settings (unsubscribe_token)
    WHERE unsubscribe_token IS NOT NULL;
