# Users Module — Data Rules

**Implementation status**: Implemented for profile and settings.
`UserController`, `UserServiceImpl`, `UserSummaryServiceImpl`, `UserRepository`, and `UserSettingsRepository` all exist.
The `push_tokens` table has no Java code at all — no entity, no repository, no service — and account soft delete and restore are likewise unimplemented.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `users` | `id`, `username`, `email`, `display_name`, `bio`, `avatar_url`, `banner_url`, `website_url`, `is_private`, `is_verified`, `status`, `role`, `deleted_at`, `registration_ip`, `last_login_ip`, `last_login_at`, `suspended_until` | Profile fields. Shared with auth module (auth owns the row lifecycle; users module manages profile fields). The four columns added in V56 are written by the auth and admin modules only and are never exposed on a public profile shape; see `auth/DATA_RULES.md` and `admin/DATA_RULES.md`. |
| `user_settings` | `user_id` (PK/FK), `notify_*`, `show_activity_status`, `allow_story_replies`, `allow_message_requests` | Per-user notification and privacy preferences. Created alongside the user account. |
| `push_tokens` | `id`, `user_id`, `token`, `platform`, `last_used_at` | Device push notification tokens. Multiple tokens per user (one per device). **Schema only — no application code reads or writes this table.** |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `users.follower_count` | `users` table | `COUNT(*)` from `follows` where `following_id = user.id` and `status = 'accepted'` | Trigger `trg_follow_counts` (V16) |
| `users.following_count` | `users` table | `COUNT(*)` from `follows` where `follower_id = user.id` and `status = 'accepted'` | Trigger `trg_follow_counts` (V16) |
| `users.post_count` | `users` table | `COUNT(*)` from `posts` where `user_id = user.id`, `status = 'published'`, `deleted_at IS NULL` | Trigger `trg_post_count` (V16) |
| `users.updated_at` | `users` table | Auto-maintained | Trigger `trg_users_updated_at` (V16) |
| Username search index | GIN index `idx_users_username_trgm` (V15) | Rebuild by reindexing `users` | Automatic on write |
| Username + display-name FTS index | GIN index `idx_users_fts` (V15) | Rebuild by reindexing `users` | Automatic on write — **currently unused by any query** |

There is no Redis cache of user profiles.
No `@Cacheable` annotation and no `users:*` Redis key exists anywhere in the tree; profile reads always hit PostgreSQL.

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `username` max 30 characters, unique, not null | `VARCHAR(30) UNIQUE NOT NULL` (V02) |
| `username` identity is **case-insensitive**; `Alice` and `alice` are one account | `UNIQUE` functional index `idx_users_username_lower` on `lower(username)` (V42). The plain `UNIQUE` on the raw column from V02 is retained as a structural guard |
| `username` storage is **case-preserving**; the column holds exactly what the caller submitted | No write path normalizes the value. `AuthServiceImpl.register` and `UserServiceImpl.updateMyProfile` store the raw input, and the V42 migration deliberately does not lowercase existing rows |
| `email` max 255 characters, unique, not null | `VARCHAR(255) UNIQUE NOT NULL` (V02) |
| `email` identity is **case-insensitive**; `Alice@example.com` and `alice@example.com` are one account | `UNIQUE` functional index `idx_users_email_lower` on `lower(email)` (V44). The plain `UNIQUE` on the raw column from V02 is retained as a structural guard |
| `email` storage is **case-preserving**; the column holds exactly what the caller submitted | No write path normalizes the value, mirroring the username treatment |
| `display_name` max 100 characters | `VARCHAR(100)` (V02) |
| `is_private` defaults to `FALSE` | `DEFAULT FALSE NOT NULL` (V02) |
| Counter columns are non-negative | `CHECK (column >= 0)` (V02) |
| `push_tokens.platform` must be one of `'ios'`, `'android'`, `'web'` | `CHECK (platform IN ('ios', 'android', 'web'))` (V03) |
| `push_tokens.token` is globally unique | `UNIQUE` constraint (V03) |
| Deleting a user cascades to `user_settings` and `push_tokens` | `ON DELETE CASCADE` on FK references |
| Queries must filter `WHERE deleted_at IS NULL` for active users | Soft-delete pattern; partial index `idx_users_username` carries the predicate |

Note that `idx_users_username_trgm` and `idx_users_fts` carry **no** `deleted_at IS NULL` predicate, unlike `idx_users_username`.
Queries using them must filter soft-deleted rows themselves.

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Profile update cannot change `email` | Enforced by construction: `UpdateProfileRequest` has no `email` field and is annotated `@JsonIgnoreProperties(ignoreUnknown = false)`, so sending one is rejected |
| `username` change checks uniqueness **table-wide, including soft-deleted rows** | `UserServiceImpl.updateMyProfile` — `userRepository.existsByUsername`, throws `USER_USERNAME_ALREADY_EXISTS` (409) |
| `username` must match `^[a-zA-Z0-9_.]+$` and be 3–30 characters | `UpdateProfileRequest` bean validation |
| An empty string clears `bio`, `avatarUrl`, `bannerUrl`, `websiteUrl`; `null` leaves the field untouched | `UserServiceImpl.updateMyProfile` |
| A block in either direction hides the target profile entirely | `UserServiceImpl.getUserProfile` — returns `NOT_FOUND` so a blocked caller cannot confirm the account exists |
| User search matches `username` case-insensitively via `ILIKE '%q%'` on `idx_users_username_trgm`, never the `%` similarity operator | `UserRepository.searchByUsername` — the similarity operator returned all 200,000 rows and discarded 174,846 on recheck in measurement |
| User search requires authentication, excludes the viewer, and excludes non-`active` and soft-deleted accounts | `UserSearchServiceImpl.searchUsers`; the `authenticated()` matcher must precede the `/{userId}` `permitAll` matcher in `SecurityConfig`, since that template also matches `/users/search` |
| User search excludes any account in a block relationship with the viewer, in either direction | `UserRepository.searchByUsername` — a bidirectional `NOT EXISTS` against `blocks`, matching the stealth block model every other list applies. There is no `isBlockedBy` field on the wire at all; `ViewerRelationshipResponse` carries only `isBlocking`, since no response surface may confirm "this user has blocked the viewer" |
| User search rejects a query shorter than 2 characters and caps reachable offset at 10,000 | `UserSearchServiceImpl` and `OffsetCursorCodec.MAX_OFFSET` |
| User search has no circuit breaker; database availability failures propagate rather than becoming an empty page | An empty page would be indistinguishable from "no such user" |
| Username lookup is **case-insensitive** and shares the id lookup's gating path | `UserServiceImpl.getUserProfileByUsername` — resolves via `findByUsernameAndDeletedAtIsNull`, which compares `lower(username)` on both sides, then the same `assemblePublicProfile` used by the id lookup, so the two cannot drift on block handling or counter masking. The response echoes the stored casing, not the casing that was queried |
| Absent, soft-deleted, and block-hidden accounts are indistinguishable on lookup | All three yield `NOT_FOUND` with an identical response body apart from the timestamp |
| Social counts are relationship-gated | `UserServiceImpl.getUserProfile` — owner always sees them; a private account reveals them only to accepted followers; a public account reveals them to any authenticated caller; everyone else receives `null` |
| Every user-referencing response carries the viewer's relationship state | `UserServiceImpl.getUserProfile` via `SocialService.loadRelationships` |
| A soft-deleted or unknown user id resolves to a placeholder, not an error | `UserSummaryServiceImpl.loadSummaries` — `displayName` becomes `"Deleted user"`, `username` becomes `null` |
| Batch user lookups are a single query regardless of id count | `UserRepository.findSummariesByIdIn` |
| `user_settings` is created with defaults when a new user registers | `AuthServiceImpl.register` and `CustomOidcUserService` (OAuth path) |
| `status` transitions are admin-only and follow a fixed state machine | `AdminServiceImpl` — ban, unban, suspend, unsuspend; each writes an `admin_actions` audit row in the same transaction. A moderator cannot reach these at all, an actor cannot change its own status, and no actor can change an administrator's status. See `admin/DATA_RULES.md` |

**Username and Email Retention on Soft Delete**:

- `UNIQUE` constraints on `username` and `email` are enforced even when `deleted_at IS NOT NULL`.
- Soft-deleted accounts retain their `username` and `email`. These identifiers are not reusable by other accounts.
- See `GLOBAL_RULES.md` — Username and Email Retention on Soft Delete for full details.

### C. Not Implemented

These rules are stated in the schema's intent but have no application code behind them.

| Rule | Status |
|------|--------|
| A push token must be de-registered when the user logs out from a device | Not implemented — the `push_tokens` table has no entity, repository, or service |
| Soft delete of a user must set `deleted_at = NOW()` and must not hard-delete | Not implemented — no code path sets `users.deleted_at`; reads filter on it, but nothing writes it |
| Restoring a soft-deleted user must set `deleted_at = NULL` | Not implemented |

### D. Known and Accepted Residual Disclosure

`users.follower_count`, `users.following_count`, and `users.post_count` are trigger-maintained (Section 2) and viewer-blind: every caller who can see the counter at all sees the identical number, regardless of that caller's own block relationships.

That viewer-blindness is a weak signal, not a safeguard.
A block-filtered list and a viewer-blind counter can disagree by subtraction.
Concretely: C blocks A.
Both A and C follow M.
A reads M's follower list — which `social`'s follower-listing filter (see `docs/modules/social/DATA_RULES.md`) excludes accounts in a block relationship with A from — and separately reads M's `followerCount`, which does not.
If the list is short one row relative to the count, A learns that some account in M's follower set is in a block relationship with A.
Repeating this across every profile A and C both follow, and intersecting the results, narrows the candidate set — though A never learns which account it is, only that at least one exists.

This was evaluated and the counters were left unchanged.
Computing a counter per viewer would require a live count query on every profile read instead of the trigger-maintained column, and would not be a simple filter — it would need to run per viewer, since the same profile is read by many different viewers with different block sets.
That cost was judged not worth closing a signal this weak: it discloses that a block exists somewhere in an intersection, never whose.

A future reader must not re-derive the "counters are safe because they're viewer-blind" reasoning and must not treat this as a defect still open for a simple fix — it is accepted, for the stated reason, and the trade-off has already been made.

### E. Scope Simplifications

- `users.avatar_url` and `users.banner_url` are plain `TEXT` CDN URLs, not foreign keys to `media_assets`.
  This avoids enforcing deletion ordering but means avatar/banner assets and profile are not referentially linked.
  The client supplies the CDN URL directly on profile update; the users module performs no `media_assets` lookup.
- No account deactivation self-service flow; `status` changes are admin-only actions.
- Username identity is case-insensitive and storage is case-preserving: `Alice` and `alice` are one account, and whichever casing was submitted is what the profile renders.
  The impersonation vector previously recorded here is closed by the `lower(username)` unique index added in V42.
- The rename policy for accounts that already collided before V42 remains an open product decision.
  The migration deliberately fails closed rather than auto-resolving: a database holding two accounts whose usernames differ only by case blocks deployment with `Username case collision detected` until an operator reconciles them.
  Silently renaming or merging a live account is not a decision a migration should make.
- Username availability is checked table-wide, including soft-deleted accounts, because soft delete does not release a username and no purge job exists.
  See `GLOBAL_RULES.md` section 3.
  `UserRepository.existsByUsername` is the single guard on every write path: registration, OAuth username generation, and profile update.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `auth` | inbound | Auth module owns the `users` row lifecycle (creation, status validation); users module manages profile fields on the same row |
| `media` | outbound | `avatar_url` and `banner_url` are CDN URLs sourced from `media_assets`; relationship is by convention, not FK |
| `notification` | inbound | `UserNotificationPreferencesService` gives the notification module the `user_settings` toggles it checks before writing; `UserSummaryService` gives the actor summaries and the verified flag of a feed row |
| `social` | outbound | `UserServiceImpl` calls `SocialService` for block gating, follow gating, and viewer relationship state |
| `admin` | inbound | Moderation actions mutate `users.status` and write an `admin_actions` audit row |
| all modules | outbound | `UserSummaryService` is the shared batch resolver for embedding user identity in any response |
