# Social Module — Data Rules

**Implementation status**: Follow create, approve, decline, and unfollow are implemented, as are block and unblock, follower and following listing, pending follow request listing, and the `user.followed.v1` / `user.follow-requested.v1` notification consumer.
The only outstanding rule is transitioning pending follow requests to accepted when a private account switches to public; see Section 3B.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `follows` | `follower_id`, `following_id`, `status`, `created_at`, `accepted_at` | Unidirectional follow relationships have pending status for unapproved private-account requests and record the acceptance time in `accepted_at`, which is null while pending. |
| `blocks` | `blocker_id`, `blocked_id`, `created_at` | Block relationships. Bidirectional in effect: blocked user cannot follow, message, or see the blocker's content. |
| `user_mutes` | `user_id`, `muted_user_id`, `mute_posts`, `mute_stories`, `created_at`, `updated_at` | One row per muting user and muted account (V138). At least one of the two scopes is true; a row with both scopes off is an unmute and is deleted instead. A self-mute is rejected. `updated_at` is maintained by trigger `trg_user_mutes_updated_at`. |
| `account_show_fewer` | `user_id`, `author_id`, `created_at` | A user asking to see less of one author in recommendations (V138). A self row is rejected. |
| `interaction_limits` | `user_id` (PK/FK), `limit_non_followers`, `limit_recent_followers`, `expires_at`, `created_at`, `updated_at` | At most one temporary interaction limit per user (V138). At least one toggle is true. A row past `expires_at` is inactive but may remain until replaced or removed. `updated_at` is maintained by trigger `trg_interaction_limits_updated_at`. |
| `hidden_words` | `id`, `phrase`, `normalized_phrase`, `created_by`, `created_at`, `updated_at` | The platform-wide hidden word list that administrators maintain (V140). `normalized_phrase` is unique. `created_by` is set to null when the creating account is deleted. `updated_at` is maintained by trigger `trg_hidden_words_updated_at`. |
| `user_hidden_words` | `id`, `user_id`, `phrase`, `normalized_phrase`, `created_at` | A user's personal hidden word list (V140). `(user_id, normalized_phrase)` is unique. `phrase` keeps the text as the user typed it and `normalized_phrase` is the output of `TextMatchNormalizer.normalizePhrase`, which is the only form ever compared. The per-user entry limit of 200 is enforced by the service. |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `users.follower_count` | `users` table | `COUNT(*)` from `follows` where `following_id = user.id` and `status = 'accepted'` | Trigger `trg_follow_counts` (V16) |
| `users.following_count` | `users` table | `COUNT(*)` from `follows` where `follower_id = user.id` and `status = 'accepted'` | Trigger `trg_follow_counts` (V16) |
| Platform-wide hidden word matcher | In memory in `HiddenWordServiceImpl` (`AtomicReference<PhraseMatcher>`) | `hidden_words.normalized_phrase`, every row | Loaded at application start, and reloaded by an `AFTER_COMMIT` listener on `HiddenWordsChangedEvent` or by `HiddenWordService.refreshSystemList()`; a single application instance is assumed, so a second instance would serve its own copy until restarted |
| Pending follow requests view | `pending_follow_requests` (DB view, V17) | Built from `follows` where `status = 'pending'` | Query-time; not currently queried by application code — `SocialServiceImpl.getPendingFollowRequests` reads `follows` directly via keyset-paginated repository methods |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| A user cannot follow themselves | `CHECK (follower_id <> following_id)` on `follows` |
| A user cannot block themselves | `CHECK (blocker_id <> blocked_id)` on `blocks` |
| `follows.status` must be one of `'pending'`, `'accepted'`; defaults to `'accepted'` | `follow_status` enum, `DEFAULT 'accepted'` |
| At most one follow relationship per (follower, following) pair | Compound `PRIMARY KEY (follower_id, following_id)` |
| At most one block relationship per (blocker, blocked) pair | Compound `PRIMARY KEY (blocker_id, blocked_id)` |
| Deleting a user cascades to their follow and block rows | `ON DELETE CASCADE` on all FK references |
| `follower_count` and `following_count` on `users` are updated atomically with follow state changes | Trigger `trg_follow_counts` fires on INSERT / UPDATE / DELETE of `follows` |
| Counter increment happens only when `status = 'accepted'`; pending follows do not increment counters | Trigger `fn_follow_counts` logic |
| `accepted_at` is set when a follow becomes accepted, stays unchanged on same-status updates, and is null while pending | Trigger `trg_follow_accepted_at` calls `fn_follow_accepted_at` |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| The blocked list returns the viewer's **outgoing** blocks only, newest first; incoming blocks are not exposed by any endpoint | `SocialServiceImpl.getBlockedUsers` |
| The blocked list keysets on the `(created_at, blocked_id)` row-value tuple, served by `idx_blocks_blocker_created_blocked` (V40) | `BlockRepository.findFirstBlocked` / `findBlockedBefore` |
| A blocked account since soft-deleted stays in the list as a placeholder rather than being dropped | `SocialServiceImpl.getBlockedUsers` via `UserSummaryService.loadSummaries` |
| When target account has `is_private = TRUE`, a new follow row is created with `status = 'pending'` | `SocialServiceImpl.followUser` |
| When target account has `is_private = FALSE`, a new follow row is created with `status = 'accepted'` | `SocialServiceImpl.followUser` |
| Approving a follow request updates `follows.status` from `'pending'` to `'accepted'` | `SocialServiceImpl.respondToFollowRequest` |
| Declining a follow request deletes the `follows` row | `SocialServiceImpl.respondToFollowRequest` |
| Unfollowing deletes the `follows` row (triggers counter decrement) | `SocialServiceImpl.unfollowUser` |
| Blocking a user must also delete any existing follow rows in both directions | `SocialServiceImpl.blockUser` |
| A blocked user must be excluded from follower/following lists and the accepted-following feed set | `FollowRepository` (block-exclusion subqueries on the followers/following keyset queries), `SocialServiceImpl.getAcceptedFollowingExcludingBlocks` |
| A follow request records `user.follow-requested.v1`; an accepted follow records `user.followed.v1` in the transactional outbox | `SocialEventServiceImpl` |
| The `user.followed.v1` / `user.follow-requested.v1` events are consumed to create notifications | `SocialNotificationConsumer` (`notification` module) |
| Unfollow, approve, reject and block publish `user.unfollowed.v1`, `user.follow-request.approved.v1`, `user.follow-request.rejected.v1` and `user.blocked.v1` through the outbox, in the same transaction as the relationship write, so the feed retracts, converts or removes the affected rows | `SocialServiceImpl`, `SocialEventService` |
| When a private account is made public, all `'pending'` follow rows for that account must be transitioned to `'accepted'` | `[NOT YET IMPLEMENTED]` — `UserServiceImpl.updateMyProfile` flips `is_private` only; no pending-follow transition runs |
| A personal hidden word is validated before anything else: 1 to 64 raw characters counted by code point, and 1 to 6 tokens after normalisation; punctuation-only, blank and missing phrases, and phrases containing a NUL or any other control character after stripping, fail with `HIDDEN_WORD_INVALID` | `HiddenWordPhrases.normalizeOrReject`, called by `HiddenWordServiceImpl.addPersonal` and `HiddenWordLifecycleServiceImpl` |
| A personal entry is stored with the phrase as typed (stripped) and its normalised form; matching and the duplicate check use the normalised form only, so `Đồ Ngu` then `do ngu` is a duplicate (`HIDDEN_WORD_DUPLICATE`) | `HiddenWordServiceImpl.addPersonal` |
| An account keeps at most 200 personal entries (`HIDDEN_WORD_LIMIT_REACHED`); the count, the duplicate check and the insert run under a transaction-scoped advisory lock per account, so concurrent adds at 199 entries admit exactly one | `HiddenWordServiceImpl.addPersonal`, `UserHiddenWordRepository.lockPersonalList` |
| Removing an entry of another account, or an unknown entry, answers `NOT_FOUND` and changes nothing | `UserHiddenWordRepository.deleteByIdAndUserId` |
| `matcherFor(ownerId)` combines the owner's personal entries with the cached platform-wide list when `hidden_words_system_enabled` is true, and with the personal entries alone when it is false; it costs one personal-list query and one settings lookup per call | `HiddenWordServiceImpl.matcherFor` |
| `matcherFor(ownerId)` for an account with no `user_settings` row logs an error and applies the column defaults, so the platform-wide list is on, instead of failing the actor whose comment or message is being checked | `UserPrivacySettingsServiceImpl` |
| `hidden_words` is written only by `HiddenWordLifecycleServiceImpl`, which the admin module calls for the guard and the audit row; a phrase obeys the same bounds as a personal entry and a duplicate normalised form answers `HIDDEN_WORD_DUPLICATE` | `HiddenWordLifecycleServiceImpl`, `AdminHiddenWordServiceImpl` |
| Editing an entry may change its case or accents without being a duplicate of itself, while another entry's normalised form is a duplicate | `HiddenWordRepository.existsByNormalizedPhraseAndIdNot` |
| Two writers racing past the duplicate check are refused by the unique constraints `hidden_words_normalized_phrase_key` and `user_hidden_words_user_id_normalized_phrase_key`, which the global exception handler answers as `HIDDEN_WORD_DUPLICATE` rather than a generic conflict | `GlobalExceptionHandler.CONSTRAINT_ERROR_CODES`, pinned by `HiddenWordDuplicateConstraintIT` |
| Every change to `hidden_words` publishes `HiddenWordsChangedEvent` inside its transaction, and the cached list reloads only after commit, so a rolled-back change never reaches the cache | `HiddenWordLifecycleServiceImpl`, `HiddenWordServiceImpl.onHiddenWordsChanged` |
| Reloading the cached list is serialised, so two overlapping reloads cannot leave the older snapshot in place; a failed reload keeps the previous list, logs at error, and does not fail the request that made the change | `HiddenWordServiceImpl.refreshSystemList`, `HiddenWordServiceImpl.onHiddenWordsChanged` |

**Block directionality**:
- The `blocks` table stores a single directional row: `(blocker_id, blocked_id)`.
- However, the practical effect is bidirectional: neither party can see the other's content, follow each other, or send messages.
- This bidirectional enforcement is an application-layer rule, not a DB constraint.
- The DB only enforces that `blocker_id ≠ blocked_id` and that the pair is unique.

### C. Known and Accepted Residual Disclosure

`users.follower_count` and `users.following_count` are trigger-maintained (Section 2) and viewer-blind: every caller who can see the counter at all sees the identical number, regardless of that caller's own block relationships.

That viewer-blindness is a weak signal, not a safeguard.
A block-filtered list and a viewer-blind counter can disagree by subtraction.
Concretely: C blocks A.
Both A and C follow M.
A reads M's follower list, which the block-exclusion subqueries on `FollowRepository` filter, and separately reads M's `followerCount`, which they do not.
If the list is short one row relative to the count, A learns that some account in M's follower set is in a block relationship with A.
Repeating this across every profile A and C both follow, and intersecting the results, narrows the candidate set — though A never learns which account it is, only that at least one exists.

This was evaluated and the counters were left unchanged.
Computing a counter per viewer would require a live count query on every profile read instead of the trigger-maintained column, and would need to run per viewer since the same profile is read by many different viewers with different block sets.
That cost was judged not worth closing a signal this weak: it discloses that a block exists somewhere in an intersection, never whose.

A future reader must not re-derive the "counters are safe because they're viewer-blind" reasoning and must not treat this as a defect still open for a simple fix — it is accepted, for the stated reason, and the trade-off has already been made.
See `docs/modules/users/DATA_RULES.md` Section 3D for the same entry from the profile-read side.

### D. Scope Simplifications

- No mutual-follow (friends) concept; the model is strictly unidirectional.
- Block relationships have no expiry or appeal mechanism.
- No "close friends" or restricted list features.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound and outbound | Inbound: `follower_id`, `following_id`, `blocker_id`, `blocked_id` all reference `users.id`; counters written back to `users`. Outbound: `SocialServiceImpl` calls `UserSummaryService` to resolve a public summary (with a deleted/unknown placeholder) for each pending-request requester |
| `notification` | outbound | Follow, request, unfollow, approve, reject and block events drive notification creation, retraction and in-place conversion |
| `notification` | inbound | `SocialService` answers block checks, follow status, the relationship block of a feed row, and the pending-request summary of the pinned entry (`countPendingRequestsUpTo`, `summarizePendingFollowRequests`) |
| `post` | inbound | Post visibility (private account) is gated by `follows.status = 'accepted'` |
| `users` | outbound | `HiddenWordServiceImpl` reads `hidden_words_system_enabled` through `UserPrivacySettingsService`; the personal hidden words endpoints are served by the users module's `UserSafetyController` |
| `message` | inbound | Messaging permissions depend on whether a block relationship exists |
