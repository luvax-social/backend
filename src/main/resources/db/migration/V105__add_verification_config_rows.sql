-- Config rows for the enum values V104 added.
--
-- An enum value without a config row breaks the vocabulary surface, which reads both of these
-- tables to render its lists, so the pair is added together exactly as V98/V99 did.

-- allows_public_form is FALSE. A verification request is about an account, so it needs an account;
-- the public form produces a ticket before any account is resolved and could not carry the
-- structured request at all.
--
-- is_appeal is FALSE, and that is load-bearing rather than cosmetic. The appeal-requires-admin rule
-- in SupportAuthorizationServiceImpl reads SupportCategory.isAppeal(), so a TRUE here would be the
-- one thing standing between a moderator and the verification queue they are meant to work.
INSERT INTO support_category_configs
    (category_key, display_name, description, is_appeal, allows_public_form, is_enabled, sort_order)
VALUES
    ('verification_request', 'Request verification',
     'Ask for a verified badge on this account.', FALSE, FALSE, TRUE, 9)
ON CONFLICT (category_key) DO NOTHING;

-- requires_reason differs across the three on purpose.
--
-- Granting takes nothing away and needs no justification beyond the evidence already on the
-- request, so the reason stays optional. Rejecting and revoking both deny or withdraw something the
-- account can see, and the person on the other end is told why, so both demand a reason.
--
-- is_reversible is TRUE for all three: a rejected request can be resubmitted, a revoked badge can
-- be granted again, and a grant can be revoked.
INSERT INTO moderation_action_configs
    (action_key, display_name, requires_reason, is_reversible, is_enabled)
VALUES
    ('grant_verification',  'Grant Verification',  FALSE, TRUE, TRUE),
    ('reject_verification', 'Reject Verification', TRUE,  TRUE, TRUE),
    ('revoke_verification', 'Revoke Verification', TRUE,  TRUE, TRUE)
ON CONFLICT (action_key) DO NOTHING;
