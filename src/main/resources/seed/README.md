# Development Database Seed

A seed run populates a local PostgreSQL instance with a full, internally consistent social-network
dataset - 90 users, 722 posts, a follow graph, comment threads, stories, direct messages, a
moderation history with a working discipline ladder, notifications, and 12 months of analytics -
so a developer or QA reviewer can exercise every surface of the application, including the admin
panel, against realistic data instead of an empty database.
By default it refuses to run against anything but a local database, and every seed component is
gated to the `dev` profile.
It also runs under the `prod` profile when `seed` is explicitly active alongside it
(`SPRING_PROFILES_ACTIVE=prod,seed`), which additionally requires
`SEED_REQUIRE_LOCAL_DATASOURCE=false` to permit a non-local target.
This combination is accepted only for a disposable staging/demo database - never point it at a
database holding real user data, since `SeedResetService.reset()` truncates every seedable table
before reseeding.

| Content | Volume |
|---------|--------|
| Users | 90 (8 fixed QA accounts, 82 generated) |
| Posts | 722 (image/video/carousel/text) |
| Hashtags | 152 |
| Media assets (Pexels-sourced, R2-hosted) | 165 (125 images, 15 videos, 25 banners) |
| Avatars (randomuser.me, externally hosted) | 90, one per user, never uploaded to R2 |
| Comment pool entries | 900 across 22 topic pools |
| Conversations / messages | 85 conversations, ~1,600 messages |
| Moderation cases (full narrative) | 6 |
| Reports | ~180 (narrative + standalone) |
| Warnings / strikes | 27 / 14 |
| Support tickets / verification requests | 70 / 10 |
| Personas | 10 |

## Running the seed

Bring up the local stack (`docker compose up -d`) so PostgreSQL is reachable at `localhost`, then
start the application with both settings passed as real environment variables at process start,
for example:

```
SPRING_PROFILES_ACTIVE=dev,seed SEED_DATA=true ./mvnw spring-boot:run
```

**`SPRING_PROFILES_ACTIVE` must not be placed in `.env`.** `.env` is loaded by
`spring.config.import` (`application.yaml`), and Spring Boot resolves which profiles are active
before that import is applied - a value for `SPRING_PROFILES_ACTIVE` inside `.env` is silently
ignored for profile activation, even though ordinary properties in `.env` (including `SEED_DATA`)
work normally. Export it in your shell, pass it as a command-line/IDE run-configuration variable,
or prefix the start command as shown above. `SEED_DATA` has no such restriction and may be set in
`.env`, on the command line, or exported, since it is a plain `@ConditionalOnProperty` check, not
profile selection - `.env.example` deliberately lists `SEED_DATA` but not `SPRING_PROFILES_ACTIVE`
for this reason.

Both settings are required, for different reasons:

- `SEED_DATA=true` is what actually triggers `SeedRunner` (`@ConditionalOnProperty(name =
  "SEED_DATA", havingValue = "true")`). The `dev` profile alone never runs a seed.
- The `seed` profile (`dev,seed` - profile order matters, `seed` must be listed last so its
  overrides win) activates `application-seed.yml`, which holds off the five
  notification-producing consumers (`mail`, `notification`, `comment`, `story`, `admin`) for the
  duration of the run. Those five would otherwise stamp notification rows with `NOW()` while the
  seeder replays historical activity; `NotificationSeedWriter` writes those rows directly with
  historically correct timestamps instead. `SEED_DATA=true` alone, under plain `dev`, would run
  the seed with all eight consumers active, because `application-dev.yml`'s literal `true` values
  are not overridden by an environment variable at that point - only the `seed` profile's own file
  overrides them.
  The `hashtag`, `post`, and `recommendation` consumers stay enabled during a seed run: their side
  effects (Elasticsearch sync, Gorse feedback) are safe and are in fact the point of the run's
  outbox-emission phase (see below).

`SeedRunner` also refuses to run unless `app.seed.require-local-datasource` (default `true`, set
via `SEED_REQUIRE_LOCAL_DATASOURCE`) is enabled and the configured JDBC URL resolves to
`localhost`, `127.0.0.1`, or the IPv6 loopback, since the reset step truncates every seedable
table.
A `@PostConstruct` check also refuses to run unless the `seed` profile is active alongside `dev`,
naming the exact `SPRING_PROFILES_ACTIVE=dev,seed SEED_DATA=true` command in its failure message -
`SEED_DATA=true` under plain `dev` can no longer silently run the seed with the wrong consumers
active.

What happens on that run:

1. `SeedResetService.reset()` purges every declared RabbitMQ queue (both the working queues and
   their dead-letter queues, discovered from the `Queue` beans the topology config declares rather
   than a hardcoded list), deletes and recreates the `posts` and `hashtags` Elasticsearch indexes
   with their real mapping, truncates Gorse's own sibling Postgres database (`GORSE_DATA_STORE` in
   `docker-compose.yaml`), and only then truncates every seedable domain table.
   This is destructive: any local data, queued message, indexed document, or recommender state you
   had before the run is gone.
   The broker and search/recommender state are purged first, specifically so a message already in
   flight when the purge starts cannot be delivered against a database this call is about to
   truncate - without this, a second run against an already-seeded stack replays stale queue
   messages and dead-letters on a foreign key violation, and Elasticsearch/Gorse accumulate
   duplicate or orphaned entries across runs.
2. Every domain writer runs in dependency order (users and media first, then posts, comments,
   engagement, social graph, stories, messages, moderation history, notifications, analytics).
3. `SeedOutboxEmitter` replays a bounded set of real domain events (search-index updates, and
   engagement events for every like, save, and comment) through the same transactional outbox
   every production write path uses, so the real Elasticsearch index-sync and Gorse recommendation
   consumers index and learn from the seeded dataset.
4. The run asserts every value of 16 mandatory enum-typed columns (`user_status`, `post_type`,
   `admin_action_type`, `event_type`, and so on) is represented on at least 5 rows, and fails loudly
   if any value falls short.

A seed run only ever executes once per JVM process: a marker system property is set the moment a
run starts, so a Spring Boot DevTools hot restart (which reuses the same JVM and the same broker,
search, and recommender state) does not silently trigger a second reset and reseed on top of
whatever the first run already wrote. Stop and start the application to force a genuinely new run.

## QA accounts

Every seeded account shares the plaintext password `Password123!`.
Eight accounts have fixed, stable usernames specifically for manual QA and reference in test
fixtures - every other account is generated content and should not be relied on by name.

| Username | Purpose |
|----------|---------|
| `admin` | Full administrator (role `admin`). |
| `mod1` | Moderator (role `moderator`). |
| `user_public` | Ordinary active public account with content. |
| `user_new_empty` | Created 2 days ago, zero posts, zero followers, zero following. |
| `user_power` | High-volume account: 20 posts, high follower count, high engagement. |
| `user_private` | Private account with pending follow requests inbound. |
| `user_suspended` | Suspended with an active suspension expiry in the future. Tied to `moderation_cases.json` case 1 (spam escalation). |
| `user_banned` | Banned, with a full moderation history. Primary target of `moderation_cases.json` case 2 (scam account). |

## Admin conversations

`admin` participates in 25 of the 85 seeded conversations - the other 60 are between generated
accounts and never involve `admin` at all, which is why a pre-Phase-6 seed left `admin`'s own inbox
empty.
The 25 partners are chosen deliberately, not drawn at random: every persona in `personas.json` is
represented by at least one partner, and each conversation's subject matter matches its partner's
persona (a photographer negotiates a shoot, a shop owner handles an order and exchange, a developer
walks through a code review, and so on).

| Property | Actual |
|---|---|
| Conversations involving `admin` | 25 |
| Long (30-50 messages) | 8 |
| Medium (8-20 messages) | 10 |
| Short (2-5 messages) | 5 |
| Single unanswered message | 2 |
| `image` / `video` / `post_share` / `story_share` messages | 40 / 8 / 12 / 5 |
| Conversations unread for `admin` | 8 |

Within those 25, the set also exercises every state `message/DATA_RULES.md` describes: at least one
administratively-removed message (including one that carried an image, proving the read path
withholds media and shares, not only text), at least one sender-deleted message, one conversation
with the manual unread flag (`conversation_participants.is_manually_unread`, V53) set on an
otherwise fully-read thread, and one conversation where `admin` has set a private nickname
(`conversation_participants.nickname`, V52) for the other participant.

`post_share` and `story_share` messages reference real rows: a `post_share` resolves via
`PostSeedWriter`'s seed-id map to an actual published post, and a `story_share` resolves via a
seed-time-only lookup (`MessageSeedWriter.fetchLiveStoryIdByOwnerUsername`) of the most recent story
a named username owns that is still live (not expired, not administratively removed) as of the seed
run's own `SeedTimeline.referenceNow()` - never SQL `NOW()`, since a fixed-instant test harness
computes story liveness relative to that same reference, not the wall clock. An image or video
message's `media_ref` is resolved the same way a post's media is: `MediaSeedWriter` mints a real
`media_assets` row owned by the message's sender for the referenced manifest entry, reusing entries
already in `media_manifest.json` rather than provisioning anything new.

## Support tickets

`SupportSeedWriter` seeds 70 `support_tickets` (every `support_category` value, 6-10 tickets each)
plus 10 `verification_requests` from `support/support_ticket_pools.json`'s pooled request, response
and escalation prose.
There is no message thread to seed: the schema carries exactly one `staff_response` and one
`internal_note` per ticket, never a conversation, so every ticket is written once in its final
resolved shape.
Every decision is attributed to one of the two fixed QA staff accounts: `appeal_*` tickets are
always decided by `admin` (a moderator may claim or escalate one but never decide it), every other
category is decided by `mod1` unless it went through an escalate-to-admin path, in which case
`admin` makes the final call.
`appeal_*` tickets link `admin_action_id` back to a real punitive `admin_actions` row
`ModerationSeedWriter` already wrote against the target account (for example a `ban_user` row for an
`appeal_ban` ticket), matching how a real signed-link appeal binds to the audit row it contests.
Every decision - `respond_support_ticket`, `reject_support_ticket`, `escalate_support_ticket` - also
writes a matching `admin_actions` row, mirroring `AdminActionRecorder`'s production shape, which is
what keeps `SeedRunner.assertEnumCoverage()`'s floor for those three action types satisfied. No
outbox event is ever enqueued for this data.

## File map

Files used across nearly every writer stay at the top level; everything else is grouped by what it
describes.

| File | Holds | Consumed by |
|------|-------|-------------|
| `personas.json` | 10 persona archetypes referenced by users, posts, and comments; each `voice` describes an English writing style | `UserSeedWriter`, `PostSeedWriter`, `CommentSeedWriter` |
| `users.json` | The 90 seed users, including the 8 fixed QA accounts | `UserSeedWriter`, and read by nearly every other writer |
| `content/posts.json` | 722 authored posts (image/video/carousel/text) | `PostSeedWriter` |
| `content/hashtags.json` | The hashtag catalog posts reference | `PostSeedWriter` |
| `content/comment_pools.json` | 900 pooled comment lines across 22 topics, plus scripted comment chains | `CommentSeedWriter` |
| `messaging/conversations.json` | 85 conversations and their message history (60 between generated accounts, 25 involving `admin`) | `MessageSeedWriter` |
| `moderation/moderation_cases.json` | 6 narrative moderation cases plus supplementary actions and reports | `ModerationSeedWriter` |
| `media/media_manifest.json` | 165 Pexels-sourced media entries already uploaded to R2 (avatars are not in this file - see "Avatars" below) | `MediaSeedWriter`, `UserSeedWriter` (banner `cdn_url` lookup) |
| `support/support_ticket_pools.json` | Pooled support-ticket request/response/escalation prose and verification claims | `SupportSeedWriter` |

## Java package map

| Package | Holds |
|---------|-------|
| `com.app.common.seed` | `SeedRunner` (orchestration entry point) and `SeedProperties` |
| `com.app.common.seed.loader` | `SeedDataLoader` (reads and validates all seed JSON) and `SeedContent` (the loaded, typed result) |
| `com.app.common.seed.model` | The 17 content record types the JSON files deserialize into |
| `com.app.common.seed.time` | `SeedTimeline`, the deterministic timestamp generator |
| `com.app.common.seed.reset` | `SeedResetService`, the pre-run table truncation |
| `com.app.common.seed.writer` | The 12 domain writers, one per subsystem |
| `com.app.common.seed.outbox` | `SeedOutboxEmitter` / `SeedOutboxBatchWriter`, which replay seeded activity through the real transactional outbox |

## Avatars

Every seeded user carries a literal, deterministic `avatar_url` authored directly in `users.json`
- a real portrait photograph from `https://randomuser.me/api/portraits/{men|women}/{0-99}.jpg`.
This is an external runtime dependency: the app never uploads or proxies these images, so a
seeded avatar is only reachable while randomuser.me itself is reachable. It was chosen because it
is the only option found that is simultaneously photographic (not illustrated, per an earlier
product decision), free with no API key or rate limit, and large enough (200 distinct images) to
give all 90 users a distinct face. Assignment is positional - alternating `men`/`women` by the
user's index in `users.json`'s array, sequential portrait index per gender bucket - so it is
stable across seed runs as long as that array's order does not change, and no two users collide.
`UserSeedWriter` writes this value straight to `users.avatar_url`; it never touches R2 or
`media_assets`. Banners are unaffected and still come from R2 via `banner_media_ref`, resolved
against `media_manifest.json`'s `cdn_url` the same way as before.

## Re-provisioning seed media

Seed media (banners and post images/video) is sourced from Pexels and provisioned to the
configured object storage bucket ahead of a seed run - the seed writers reference already-uploaded
assets rather than uploading at seed time. To (re-)provision it:

```
python scripts/provision-seed-media.py
```

Add `--verify` to check the manifest against what is actually in storage without re-uploading
anything. Assets already uploaded are skipped, not re-downloaded, so re-running the script after an
interrupted run is safe. Requires `PEXELS_API_KEY` to be set.
The script refuses to run against any R2 bucket other than the configured dev bucket name, and
writes its manifest to `src/main/resources/seed/media/media_manifest.json`.

## Determinism

`SeedTimeline` is constructed with the fixed seed `20260825L`; several individual writers
(comments, engagement, stories, notifications, analytics, the social graph, and post view counts)
carry their own separate fixed `Random` seed constants for the same reason.
None of these are derived from wall-clock time.
Two seed runs against the same JSON content therefore produce identical relative timestamps and
identical graph/distribution shapes - the same accounts get the same follower counts, the same
posts get the same comment threads, and so on - which is what makes a seeded dataset reproducible
enough to write regression fixtures against.

This determinism covers relative structure, not authored narrative: the follow graph, engagement
distribution, and analytics events are synthetically generated and reproducible, but they are not
hand-authored the way the 6 moderation cases and the QA accounts are.
A developer should not read meaning into which synthetic account follows which other synthetic
account - only the 8 fixed QA accounts and the moderation-case narratives carry deliberate,
reviewable intent.

## Known gaps

- `report_reason` enum coverage sits at exactly the 5-row minimum for several values (`nudity`,
  `violence`, `hate_speech`); there is no headroom left in `moderation_cases.json` for that specific
  enum column, so removing any existing report of one of those reasons without adding a replacement
  will fail `SeedRunner.assertEnumCoverage()`.
- The synthetic follow graph, engagement, and analytics event volumes are not independently
  cross-checked against a real product's observed distributions - they are internally consistent
  and plausible, not validated against real usage data.
