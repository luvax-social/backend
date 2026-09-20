# Mail Module — Data Rules

Scope: `com.app.modules.mail`, the two consumers that drive it, and the `email_deliveries` send log.

The module owns outbound transactional email and nothing else.
It never decides that mail should be sent; it renders and delivers what a consumer hands it.

---

## Section 1: Canonical Data

| Table | Owner | Notes |
|-------|-------|-------|
| `email_deliveries` | mail | One row per send attempt (V96). Append-only except for the status transition and `sent_at`. |

`email_deliveries` outlives its recipient.
`recipient_user_id` is `ON DELETE SET NULL` while `recipient_email` is `NOT NULL`, so a hard delete removes the link to the account and leaves the record that a message was sent and where it went.

No other table belongs to this module.
Template content is source, not data, and lives under `src/main/resources/templates/mail/`.

### The mail campaign feature was withdrawn

Between V101 and V113 this module also owned `mail_campaigns`, `mail_campaign_recipients` and `mail_templates`, an administrator-composed bulk mail feature with its own scheduler, its own Markdown rendering pipeline and a per-user unsubscribe token on `user_settings`.
It was never documented here, and the sentence above was wrong for as long as it existed.

V114 withdrew all of it as a product decision.
The three tables were archived into `archived_mail_templates`, `archived_mail_campaigns` and `archived_mail_campaign_recipients` rather than dropped outright, following V50, and the two campaign enum types were dropped with them.
`admin_action_type` lost `send_mail_campaign`, which had never been reachable: the Java enum `AdminActionType` never declared it and no campaign class ever wrote an `admin_actions` row.

`email_deliveries` survives and is unaffected.
The auth, moderation and support lanes all write to it, so it was never campaign-specific.
`AbstractTemplateMailSender` lost only its `sendCampaign` method; the recipient allowlist every lane routes through is untouched.

Do not reintroduce this by adding a table here.
A future bulk mail feature is a product decision to take again, not one to infer from the archive tables.

---

## Section 2: Derived Data / Cache / Projection

| Key | TTL | Owner | Purpose |
|-----|-----|-------|---------|
| `auth:ratelimit:mail:moderation:{sha256(email)}` | 1 hour sliding | `ModerationMailThrottleImpl` | Per-recipient moderation mail budget |

The address is hashed into the key rather than embedded, so reading Redis keys does not enumerate everyone the platform has disciplined.

The key carries the `auth:ratelimit:` prefix because the throttle delegates to the existing `RateLimiterService` rather than restating its Lua script.
One audited sliding-window implementation is worth the cosmetic cost of a prefix that names the wrong module.

---

## Section 3: The Send Path

### Transport selection and guard

`app.mail.transport` selects the transport, and each implementation is registered by `@ConditionalOnProperty` on that value.

| Transport | Bean | Registered when |
|-----------|------|-----------------|
| `resend` | `ResendMailSender` | `app.mail.transport=resend` |
| `noop` | `NoopMailSender` | `app.mail.transport=noop` |

`resend` is the literal value in `application.yaml` and is what production inherits.
`MailTransportGuard` refuses to boot if `noop` is selected outside the `dev` profile, so the non-network transport cannot reach an environment that has real recipients.
Surefire pins `noop` for the test phase, which is why the suite makes no outbound call.

### Rendering

`AbstractTemplateMailSender` owns every template variable map so a template gaining a variable is a one-line change for all transports.
Subclasses see only a recipient, a subject and rendered HTML.

`MailTemplateRenderer` hardcodes `Locale.ENGLISH`.
There is no internationalisation, and every template is English.

### Provider bounds

`deliver` returns the provider's message identifier, which is stored on the send log.
Before V96 the Resend response was discarded, so a delivery could not be traced at the provider afterwards.

The Resend SDK 3.1.0 exposes no HTTP configuration.
`Resend` has a single `Resend(String)` constructor, `BaseService` constructs its own client, and that client is a bare `new OkHttpClient()`.
OkHttp's own defaults therefore apply and cannot be changed: ten seconds each for connect, read and write, and no call timeout at all.

`app.mail.resend.call-timeout` is the one bound that can be imposed from outside the SDK.
`ResendMailSender` runs each send on a virtual thread and waits on it for that duration.
A timeout cancels the wait, not the socket: the SDK owns the connection and offers no way to abort it, so the call is abandoned and its thread is released when OkHttp's read timeout fires underneath.

Every transport failure becomes `ApiErrorCode.SERVICE_UNAVAILABLE` so callers observe one failure shape.
Recipient addresses and raw tokens are never logged.

---

## Section 4: Queues, Inbox and Dead-Lettering

Two independent queues feed this module.

| Queue | Consumer | Inbox consumer name | Enabled by |
|-------|----------|---------------------|------------|
| `mail.queue` | `AuthMailEventConsumer` | `auth-mail-consumer` | `app.mail.consumer.enabled` |
| `moderation.mail.queue` | `ModerationMailEventConsumer` | `moderation-mail-consumer` | `app.admin.moderation-mail.consumer.enabled` |

Both use manual acknowledgement, both deduplicate through `ProcessedMessageService.processOnce` under their own consumer name, and both take their retry policy from `app.messaging.consumer.*`.

Neither queue carries an `x-dead-letter-exchange` argument, deliberately.
That broker argument only routes a message rejected with `requeue=false`, expired on a TTL, or dropped on queue overflow.
Neither consumer rejects: each publishes the failed message to `social.events.dlx` itself through `DeadLetterPublisher` and then acks, and nacks with `requeue=true` only when that publish fails.
The argument would never fire, so it is not declared.

This is the consistent rule across the topology, not an exception to it.
Every queue whose consumer rejects with `requeue=false` carries the argument; every queue whose consumer publishes to the dead-letter exchange itself omits it.
The single queue that breaks the rule is `admin.notification.queue`, which carries an argument its consumer never triggers; it is left in place because changing the arguments of a live durable queue fails redeclaration.

---

## Section 5: The Moderation Mail Path

### Why it is separate

`AuthMailEventHandler` raises `PermanentMessageException` for any account whose status is not `ACTIVE`.
That is correct for a welcome or a password reset and must stay.

Applied to a moderation notice the same rule would suppress every message that matters.
`TokenPrincipalResolverImpl` admits only `ACTIVE` accounts, so a banned, suspended or deactivated user cannot reach any authenticated endpoint.
Those accounts are exactly the audience for a ban or suspension notice and had no way at all to learn what had happened.

`ModerationMailEventHandler` therefore applies no status gate.
The absence is the feature and is commented as such at the class level.

### What it still refuses

A soft-deleted account is refused: there is nobody left to tell.

An unverified email address is refused, recorded as `skipped` rather than sent.
The platform has never proved the address belongs to the account.
Sending a ban notice there would tell whoever actually owns that mailbox both that the address is registered here and that a moderation decision was taken about its supposed owner.
That is a disclosure to a third party who did not consent to it, and withholding the notice is the lesser harm: the account still learns the outcome when it next tries to sign in.

An account with no `user_credentials` row signed in through OAuth2 only.
The provider verified the address before it reached this system, so that account is mailed rather than skipped.

### Content rules

| Rule | Reason |
|------|--------|
| State the action and the kind of content affected | The recipient must know what happened |
| State the date | Same |
| State the end date of a fixed-term suspension | The recipient needs to know when it ends |
| Say only that the account or content did not meet community standards | Nothing more specific is safe to state |
| Never include `admin_actions.reason` | It is written for colleagues, not for the recipient |
| Never name the acting staff member | Staff safety |
| Never mention that a report exists or who filed it | Reporter safety |

The two reinstating notices suppress the community-standards line.
Telling someone their account is back and then telling them it fell short reads as a punishment rather than the reversal it is.

The payload carries the target user id, the action type, the audit row id and, for a suspension, its end date.
It carries nothing else, so the reason cannot reach a template even by accident.

### Events

Nine action types mail their subject: `BAN_USER`, `UNBAN_USER`, `SUSPEND_USER`, `UNSUSPEND_USER`, `WARN_USER`, `REMOVE_POST`, `REMOVE_COMMENT`, `REMOVE_STORY`, `REMOVE_MESSAGE`.

The mapping is closed and lives in `ModerationMailTemplates`.
No `RESTORE_*` action mails, and neither do `ISSUE_STRIKE`, `FORCE_LOGOUT`, `REVOKE_SESSION`, `CHANGE_USER_ROLE`, `REVOKE_WARNING`, `REVOKE_STRIKE`, `ESCALATE_REPORT` or any hashtag action.

`admin.moderation-notice.requested.v1` is raised by `AdminActionRecorder`, which is the single writer of `admin_actions` and joins the caller's transaction.
Raising it there rather than at each of the nine calling paths means the notice cannot be enqueued for an action that rolled back, and an action cannot commit without its notice enqueued.

The event is deliberately distinct from `user.warned.v1`.
Binding the moderation mail queue to that existing key would have tied the in-app notification and the outbound mail to one payload, one binding and one failure mode, and neither could be disabled without the other.
The cost of independence is one extra outbox row when an account is warned.

---

## Section 6: Inter-Module Dependencies

| Depends on | For |
|------------|-----|
| `users` | Recipient address and display name |
| `auth` | `user_credentials.email_verified` |
| `admin` | The action type and audit row id a notice describes |
| `common/outbox` | Event delivery |
| `common/inbox` | Duplicate suppression |
| `common/security` | The Redis sliding window behind the throttle |

Nothing depends on this module in return.
It is a leaf: it is driven by consumers and writes only its own send log.
