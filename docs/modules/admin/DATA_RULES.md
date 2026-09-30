# Admin Module — Data Rules

**Implementation status**: Fully implemented with transactional moderation actions, immutable audit queries, REST/OpenAPI endpoints, and unit/integration coverage.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `admin_actions` | `id`, `admin_id`, `action_type`, `target_user_id`, `target_entity_type`, `target_entity_id`, `report_id`, `reason`, `metadata`, `created_at`, `row_version` | Immutable audit log of every moderation action taken by an admin or moderator; PostgreSQL is the system of record and ClickHouse holds a listing replica (Section 3D). `target_user_id`, `admin_id` and `report_id` become NULL if the referenced records are deleted. `row_version` (V127) starts at 1 and is bumped by a trigger on every real change, which only a cascade can cause. |
| `user_warnings` | `id`, `user_id`, `issued_by`, `reason_key`, `note`, `admin_action_id`, `revoked_at`, `revoked_by`, `created_at` | One warning issued against an account. `admin_action_id` is NOT NULL, so a warning that no audit row explains cannot exist. `reason_key` references `report_reason_configs`, which is that table's only runtime reader. |
| `user_strikes` | `id`, `user_id`, `strike_number`, `triggered_by`, `admin_action_id`, `revoked_at`, `revoked_by`, `created_at` | One strike, the consequence of three active warnings. `strike_number` is `CHECK (>= 1)` and uncapped. |
| `luvax_analytics.platform_stats` (ClickHouse) | `bucket_start`, `metric_key`, `dimension`, `value`, `computed_at` | One value per metric, dimension and half-hour bucket, in `ReplacingMergeTree(computed_at)` ordered by `(metric_key, bucket_start, dimension)`. Long format rather than wide, because most metrics are dimensional breakdowns and a wide table would need a migration every time an enum gains a value. A re-collected bucket carries a later `computed_at` and replaces the earlier value under `FINAL`, which is the idempotency guard for a re-run. There is no daily grain. The PostgreSQL table of the same name was dropped in V130. |

The PostgreSQL tables above cannot be rebuilt from any other source if lost.
`platform_stats` in ClickHouse has **no backfill** either: a bucket that was never collected can never be collected later, because a gauge is bounded by a bucket end that has already passed and the rows it counted may since have been deleted.
Losing a row loses that point permanently.
The `admin_actions` replica, by contrast, can be rebuilt from PostgreSQL by publishing an `admin.action.recorded.v1` event for every row, which is what the seed emitter does for seeded rows; no production tool does it today.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| Action history per admin | ClickHouse `admin_actions` replica, PostgreSQL when ClickHouse is unavailable | `SELECT ... FROM admin_actions FINAL` where `admin_id = ?` ordered by `created_at DESC, toString(id) DESC` | Query-time |
| Action history per target user | ClickHouse `admin_actions` replica, PostgreSQL when ClickHouse is unavailable | The same query filtered on `target_user_id = ?` (PostgreSQL index `idx_admin_actions_target`) | Query-time |
| Platform statistics, half-hour grain | ClickHouse `platform_stats` | Counted directly in PostgreSQL from `users`, `posts`, `comments`, `stories`, `reports`, `follows`, `post_likes` and `admin_actions`, then shipped as one `admin.platform-stats.collected.v1` event per bucket | `StatsCollectionJob`, one completed bucket at a time. Never written from a Controller or Service on a request path. |
| Platform statistics, daily grain | Computed at query time from the half-hour rows | Flows summed per UTC day, gauges taking the rows of the day's last bucket | Query-time; nothing stores a daily row and no roll-up job exists |
| The current snapshot endpoint | Read straight from the newest bucket in ClickHouse | Nothing is computed at request time except the most-used hashtag list | Request-time read of stored rows; the whole response is `503 ANALYTICS_UNAVAILABLE` if ClickHouse is unavailable |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `action_type` must be one of the 36 values in `admin_action_type` enum | `admin_action_type` enum |
| `admin_actions` is an append-only log; there is no `updated_at` and no soft delete | Schema design — no such columns |
| `target_user_id` becomes NULL if the target user's account is deleted | `ON DELETE SET NULL` on `target_user_id` FK |
| `report_id` becomes NULL if the associated report is deleted | `ON DELETE SET NULL` on `report_id` FK |
| Deleting the admin user preserves their audit rows and clears the actor reference | `ON DELETE SET NULL` on `admin_id` FK (V29) |
| A real change to an `admin_actions` row bumps `row_version` and enqueues `admin.action.changed.v1` in the same transaction; a no-op update and a session under `session_replication_role = replica` do neither | Triggers `trg_admin_actions_bump_row_version` (BEFORE UPDATE) and `trg_admin_actions_enqueue_replication` (AFTER UPDATE), both `WHEN (OLD.* IS DISTINCT FROM NEW.*)` (V127) |
| `notifications.admin_action_id` must reference an existing audit row and becomes NULL if that row is deleted | `fk_notifications_admin_action`, `ON DELETE SET NULL` (V128 adds it `NOT VALID` after archiving and clearing orphans into `archived_notification_admin_action_orphans`, V129 validates it) |
| `metadata` is `JSONB` — no schema enforced at the database level; structure is defined per `action_type` by application convention | `JSONB` column |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Only users with `role = 'admin'` or `role = 'moderator'` may create `admin_actions` rows | `SecurityConfig`, `AdminController` |
| Only users with `role = 'admin'` may change an account's `status` | `SecurityConfig` (the `/api/v1/admin/users/**` matcher), method-level `@PreAuthorize` on `AdminController`, and `AdminAuthorizationService` at the service layer |
| Nobody may change their own account's `status` through the API | `AdminAuthorizationService.assertMayChangeUserStatus` |
| Nobody may change an administrator's account `status` through the API, whoever the actor is | `AdminAuthorizationService.assertMayChangeUserStatus` |
| `ban_user` action must update `users.status = 'banned'` in the same transaction | `AdminServiceImpl.banUser` |
| `unban_user` action must update `users.status = 'active'` in the same transaction | `AdminServiceImpl.unbanUser` |
| `suspend_user` action must update `users.status = 'suspended'` in the same transaction, and set `users.suspended_until` when the request carries a duration | `AdminServiceImpl.suspendUser` |
| `unsuspend_user` action must update `users.status = 'active'` in the same transaction, and clear `users.suspended_until` so the reinstatement sweep cannot re-fire on the row | `AdminServiceImpl.unsuspendUser` |
| `change_user_role` action must write `users.role` and revoke the target's refresh tokens in the same transaction | `AdminUserServiceImpl.changeRole` |
| `force_logout` action must revoke every non-revoked `refresh_tokens` row for the target and record the count in `metadata` | `AdminUserServiceImpl.forceLogout` |
| Only the transitions `user -> moderator`, `moderator -> user` and `moderator -> admin` are permitted; an administrator is never a valid target, a skip-level `user -> admin` promotion is refused, and a request naming the role already held is refused | `RoleTransitionPolicy` |
| A moderator reading the audit log sees only rows where `admin_id` equals its own id; an administrator sees every row | `AdminServiceImpl.getActions`, `getActionById`, `getActionsForUser`; the scoping runs before `AdminActionListingService` is called, so it applies to the ClickHouse listing and to the PostgreSQL fallback alike |
| `GET /admin/reports/{reportId}/target` returns the reported entity regardless of privacy, blocks or soft-delete, and is reachable only with a report identifier | `AdminReportTargetServiceImpl`, `AdminReportTargetRepository` - the report is the anchor and the whole security property: a moderator sees what somebody flagged and nothing else. An endpoint taking a bare entity identifier would be a universal privacy bypass. A moderator branch inside `PostVisibilityServiceImpl` was rejected for the same reason: the feed, the profile listing, search hydration and comment access all call it, so the branch would leak into every one of them |
| Reviewing a report target is logged at info with `reportId`, `actorId` and `entityId`, and writes no `admin_actions` row | `AdminReportTargetServiceImpl.getReportTarget` - a read that happens many times per report would dilute a table whose purpose is recording state changes |
| The report-target response is returned `Cache-Control: no-store` | `AdminController.getReportTarget` - the body is content a moderator may see only because it was reported, so it must not survive in a shared cache or a browser's back-forward store |
| A lapsed fixed-term suspension returns the account to active and records one `unsuspend_user` row with a null `admin_id` | `SuspensionExpiryServiceImpl`, driven by `UserStateValidator.enforceActive` and `SuspensionExpiryJob` |
| `metadata` is written from server-derived facts only and is never accepted from a request body | `AdminActionRecorder`, `AdminActionRequest` |
| `remove_post` must perform every side effect an owner removal performs, in the same transaction | `AdminServiceImpl.removePost` delegating to `PostService.applyModerationRemoval`. The admin module owns the transition guard and the audit row; the post module owns the side effects, so the administrative and owner removal paths cannot drift apart |
| `restore_post` must return the post to the status it held before the removal, and report that status in the audit row's `metadata.resultingStatus` | `AdminServiceImpl.restorePost` delegating to `PostService.applyModerationRestore` |
| A restore whose caption names a banned hashtag succeeds without that association, and names it in the audit row's `metadata.strippedHashtags` | `AdminServiceImpl.moderatePost` - the names are a fact the transaction established, which is the only thing `AdminActionRecorder` accepts. Refusing the restore instead would leave a moderator unable to undo its own removal because of an administrator decision it cannot reverse |
| `create_hashtag`, `ban_hashtag`, `unban_hashtag`, `edit_hashtag` and `delete_hashtag` must write the decision onto the hashtag row in the same transaction as the audit row | `AdminHashtagServiceImpl` delegating to `HashtagLifecycleService`. The admin module owns the transition guard and the audit row; the hashtag module owns the table and every side effect, the same division `remove_post` uses with the post module |
| The hashtag audit action follows the transition, not the target alone | `AdminHashtagServiceImpl.auditActionFor` - reaching `banned` is a ban and reaching `deleted` is a delete whichever state it came from, but reaching `active` is an unban only when it came from `banned`. Returning from `deleted` has no audit value of its own, and `edit_hashtag` is what that value is for |
| `delete_hashtag` never removes the hashtag row | `HashtagLifecycleServiceImpl.changeStatus` - a physical delete cascades to `post_hashtags` and drives the `post_count` trigger over every post that used the tag, which rewrites history nothing asked to rewrite and cannot be undone |
| `remove_comment` action must set `comments.deleted_at = NOW()` in the same transaction | `AdminServiceImpl.removeComment` |
| `restore_comment` action must clear `comments.deleted_at` in the same transaction | `AdminServiceImpl.restoreComment` |
| `resolve_report` and `dismiss_report` must update `reports.status` and `reports.reviewed_by` / `reviewed_at` in the same transaction | `AdminServiceImpl.resolveReport`, `AdminServiceImpl.dismissReport` |
| `admin_actions` rows must never be updated or deleted by the application once created; they are the permanent audit trail | `AdminActionRepository` exposes read and insert operations only, and every `AdminAction` column is `updatable = false`. PostgreSQL itself does rewrite a row when a foreign key `SET NULL` cascade fires, which is why `row_version` exists |
| Only an account whose role is `user` may be warned, and no actor may warn itself | `UserDisciplineServiceImpl.issueWarning` - three warnings produce a strike and a strike changes `users.status`, so a warnable moderator or administrator would hand any moderator a route to an administrator's account status |
| A warning counts toward the next strike while it is unrevoked, newer than the account's most recent unrevoked strike, and less than 90 days old | `UserWarningRepository.countActiveWarnings` - one statement, because the three conditions compose and a wrong composition changes how fast accounts are banned while failing nothing |
| The third counting warning issues a strike: number one suspends for 7 days, two for 30, three and above ban permanently | `UserDisciplineServiceImpl.issueStrike` |
| A strike's consequence is applied only when it is strictly stronger than the account's current state; the strike row is written either way | `UserDisciplineServiceImpl.applyConsequenceIfStronger` - severity order is active, then a suspension that ends, then one that does not, then a ban; within fixed-term suspensions the later end date wins, so strike two's 30 days does replace strike one's 7. Deactivated is ranked with banned so no strike undoes a self-removal |
| A strike writes a second `admin_actions` row with a null `admin_id` and metadata naming the moderator whose warning triggered it | `UserDisciplineServiceImpl.issueStrike` - the strike is the ladder's consequence, not a decision the moderator took, and recording the moderator as the actor would attribute a ban to someone who never chose one |
| Two concurrent warnings on the same account cannot both issue a strike | `AdminUserRepository.lockForDiscipline` serializes them; `uq_user_strikes_active_number` is the invariant of last resort |
| Revoking a warning revokes that warning only: no strike is reversed and `users.status` is untouched | `UserDisciplineServiceImpl.revokeWarning` |
| Revoking a strike leaves `users.status` exactly as it is; lifting the penalty is a separate decision through the account-status endpoints | `UserDisciplineServiceImpl.revokeStrike` |
| A warned account is notified through the outbox in the same transaction as the warning, with a null actor | `UserDisciplineServiceImpl.enqueueWarningNotification`, `AdminNotificationConsumer` - a named actor would run the notification block guard, so an account that had blocked the moderator would never learn it had been warned |
| An account may read its own unrevoked warnings, never its strikes and never another account's | `UserDisciplineServiceImpl.listOwnWarnings` |
| Every actor-and-target rule for a status change and for a role change is evaluated in one place | `AdminAuthorizationServiceImpl` - the two used to hold their own copies of the same three rules, so one would eventually have been updated alone. Each caller still maps the shared outcome to the error code its own contract publishes |
| A gauge metric is bounded by the bucket end, never by the moment the job happens to run | `PlatformMetric` - this is what lets the job be re-run for a past bucket after an incident and restate history rather than overwrite it with the present |
| A flow metric is a direct count over the bucket window, never a difference between consecutive gauges | `PlatformMetric` - a deletion would make such a difference negative, and a missed run would fold two intervals into one bucket with no way to detect it afterwards |
| The bucket a process starts inside is never written | `StatsCollectionJob` - a partial bucket records a fraction of an interval as a whole one, and with no backfill that low point sits at the left edge of every chart for as long as the data is kept |
| Day boundaries are pinned to UTC, not to any session timezone | `PlatformStatsAnalyticsRepositoryImpl` - `toStartOfDay` on a `DateTime('UTC')` column truncates in UTC, so two deployments in different zones cannot disagree about where a day starts |
| A daily series is computed at query time: a flow day is the sum of the day's half-hour buckets, a gauge day is the rows of the day's last bucket, and a dimension absent from that last bucket reads as missing | `PlatformStatsAnalyticsRepositoryImpl.findDailyFlowSeries`, `findDailyGaugeSeries` - the earlier roll-up took each dimension's own latest value in the day, so a dimension present at 10:00 and absent at 23:30 kept its 10:00 value; the end-of-day state is the last bucket's rows only, and `PlatformStatsDailySemanticsIT` pins the change |
| A day belongs to a daily series when its UTC midnight falls in `[from, to)`, and the day in progress is never returned | `AdminStatsServiceImpl` - `firstDay` is `from` rounded up to UTC midnight and `endDay` is the earlier of `to` rounded up and today's UTC midnight, so a partial day is never served as complete |
| Half-hour points are served only for windows starting within `app.stats.half-hour-horizon` (default 30 days); nothing is deleted | `AdminStatsServiceImpl.granularityFor` - half-hour rows are kept for good, and the horizon only keeps a chart from asking for 17,520 points |
| Statistics reads answer `503 ANALYTICS_UNAVAILABLE` for the whole response, including the live most-used hashtag list, when ClickHouse is unavailable | `AdminStatsServiceImpl` - the screen never renders half a snapshot |
| The activity log read requires a bounded time window of at most 30 days, and answers `503 ANALYTICS_UNAVAILABLE` when ClickHouse is unavailable | `AdminUserEventServiceImpl` - `user_events` lives in ClickHouse, partitioned monthly on `created_at`, so the window is what bounds the scan; the read uses `FINAL` so an unmerged duplicate never shows twice |
| The violation listing's cursor is scoped per role | `CursorScope.ADMIN_VIOLATIONS_WARNINGS` and `ADMIN_VIOLATIONS_FULL` - the listing returns different rows to a moderator and an administrator, so a shared tag would let a moderator replay an administrator's cursor into strike rows |

**`admin_id` cascade behavior** `[RESOLVED IN V29]`:
- `admin_actions.admin_id` is nullable and uses `ON DELETE SET NULL`.
- Permanently deleting an administrator preserves their immutable audit history while clearing the actor reference.

### C. Scope Simplifications

- Role-based action restrictions are coarse: an administrator may perform every `action_type`, and a moderator may perform every one except the four account-status transitions (`ban_user`, `unban_user`, `suspend_user`, `unsuspend_user`), `change_user_role`, `force_logout`, the warning and strike revocations, and the five hashtag lifecycle actions.
  The boundary was originally structural: every administrator-only endpoint lived under `/api/v1/admin/users/`, which a single matcher restricts to ADMIN.
  That is no longer the whole of it. Endpoints a moderator must not reach but that are not account administration - the warning and strike revocations, and the hashtag registry - sit outside that sub-tree deliberately, because moving them under `/users/` would mean adding exceptions ahead of the ADMIN matcher and making authorization depend on matcher ordering.
  Those endpoints are narrowed by a class-level `@PreAuthorize` instead. The rule to follow is that the `/users/` sub-tree stays ADMIN-only with no exceptions, and anything else administrator-only declares it on the controller.
  There is still no finer-grained per-action permission model.
- An administrator's account status cannot be changed through the API by anyone, so removing a rogue administrator is a database-level operation.
  This is deliberate.
  A lockout of the whole administrator tier has no in-application recovery path, because a banned account cannot authenticate and unbanning requires authentication; an escalation that requires database access does have one.
- No approval workflow for high-impact actions (e.g., banning a user does not require a second admin to confirm).
- `metadata` JSONB schema per `action_type` is convention-based, not enforced by the database.
- No admin audit log UI; audit data is exposed through role-restricted query endpoints in v1.
- The audit log is row-scoped by actor for a moderator only.
  An administrator's view is unrestricted, and there is no per-target or per-module scoping beyond that.
- `suspended_until` is meaningful only while `status = 'suspended'`, and `status` alone decides the authorization outcome on any request.
  A row with `status <> 'suspended'` and a non-null `suspended_until` is a defect, not a state to interpret.
- **A single application instance is assumed for the statistics collection job.**
  There is no distributed scheduler lock anywhere in this codebase, so two instances would each run the collection job.
  The sorting key of `platform_stats` together with `ReplacingMergeTree(computed_at)` keeps that harmless rather than duplicative: both events land on the same key and the later `computed_at` wins under `FINAL`, so a double run restates a bucket rather than doubling it.
  It still doubles the aggregate cost against PostgreSQL, so scaling this deployment out requires a scheduler lock first.
- Force logout ends refresh capability immediately but not access capability.
  The access-token blacklist is keyed on the token's own `jti`, which no administrator holds, so an access token already issued keeps working for the remainder of `ACCESS_TOKEN_TTL`.
  `WebSocketRevocationSweepService` does not close the target's live realtime sessions either, because it re-validates the access token and that token is still valid.

---

### D. Audit-Log Replication and Read Routing

PostgreSQL stays the system of record for `admin_actions`.
`AdminActionRecorder.record` enqueues `admin.action.recorded.v1` in the caller's transaction after inserting the row, and the V127 trigger enqueues `admin.action.changed.v1` when a cascade rewrites one.
`AdminActionReplicationConsumer` is notify-then-fetch: it reads the current PostgreSQL row by id and writes it to ClickHouse with its current `row_version`, so an event delivered late, twice or after a newer change can never regress the replica.
A row that no longer exists (a reseed truncated it) is acknowledged and skipped.
The listing follows the outbox, so a committed action can take a few seconds to appear in it.

Every reader was classified once, and the classification is the rule for new code:

| Reader | Store | Reason |
|--------|-------|--------|
| `AdminServiceImpl.getActions`, `getActionsForUser` (`GET /admin/actions`, `/admin/actions/users/{userId}`) | ClickHouse, PostgreSQL fallback | Listing and filtering; a few seconds of lag is accepted. The fallback keeps the same cursor contract, and `degraded` stays `false` because PostgreSQL answers completely |
| Business dashboard panels | ClickHouse | Analytics |
| `PlatformMetric.ADMIN_ACTIONS_BY_TYPE` | PostgreSQL | Counted from the system of record at bucket end, then shipped as a statistic |
| `AdminServiceImpl.getActionById` (`GET /admin/actions/{actionId}`) | PostgreSQL | Lookup by id; the detail drawer must show a row the moment the list does |
| `AdminActionRepository.findMostRecentAppealable` (`AppealRecoveryServiceImpl`) | PostgreSQL | Its anti-join against `support_tickets` needs one consistent store |
| `adminActionRepository.findById` in `SupportTicketServiceImpl` (in-product appeal and the conflict-of-interest check) | PostgreSQL | By id; a lagging replica would refuse a valid appeal, and an authorization decision must read the system of record |
| `ModerationNoticeRepository` (notification feed hydration) | PostgreSQL | By id; a notice must render the instant it exists |

The cursor is the existing `TimeCursors` microseconds plus `id`, and both stores order by `created_at DESC, id DESC` with the id compared as its canonical lowercase string, because ClickHouse orders a `UUID` by its second 8 bytes first while PostgreSQL orders it byte-wise; a walk that alternates between the stores neither skips nor repeats a row (`AdminActionClickHouseKeysetRowLossIT`).
The moderator scoping runs before the store is chosen.
A `ClickHouseRequestRejectedException` from the listing is a bug and propagates as a 500 rather than falling back.
`luvax_analytics_audit_log_fallback_total` counts fallbacks.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | `admin_id` and `target_user_id` reference `users.id`; admin actions mutate `users.status` |
| `report` | inbound | `report_id` links an admin action to the report that prompted it; `report_reason_configs` supplies the reason keys a warning may cite |
| `notification` | outbound | A warning enqueues `user.warned.v1` carrying `adminActionId`, which `AdminNotificationConsumer` turns into a `warning` notice; every other moderation notice is written synchronously by `AdminServiceImpl` with the moderator's reason and the audit row id, so the notice exists exactly when the decision does |
| `notification` | inbound | `ModerationNoticeService` gives the notification feed the kind, date, text snippet and appeal eligibility of the audit row a notice reports, to the content's author only |
| `post` | outbound | `remove_post` / `restore_post` actions mutate `posts.status` and `posts.deleted_at` |
| `hashtag` | outbound | The five hashtag lifecycle actions mutate `hashtags.status` and purge `hashtag_trending`, through `HashtagLifecycleService`. The statistics snapshot also reads the most-used active hashtags live |
| `recommendation` | inbound | The activity log reads the ClickHouse `user_events` table, which `recommendation` owns and is the sole writer of |
| `comment` | outbound | `remove_comment` / `restore_comment` actions mutate `comments.deleted_at` |
| `common/analytics` | outbound | Every ClickHouse read and write goes through `ClickHouseOperations`; `AdminActionReplicationConsumer` and `PlatformStatsIngestConsumer` are two of the four analytics listeners that `AnalyticsIngestionController` pauses while the `clickhouse` breaker is open |
