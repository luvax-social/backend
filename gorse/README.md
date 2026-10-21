# Gorse Recommender Service

Gorse v0.5.11 (`zhenghaoz/gorse-in-one:0.5.11`) provides personalized post recommendations for the `recommendation` module.
The backend talks to it over REST; no domain code depends on Gorse internals.

## Run

Gorse is part of the root compose file, so the ordinary command starts it:

```bash
docker compose up -d
```

It lived in an overlay compose file until the engagement consumer was found dead-lettering every
event: the consumer is enabled in dev and prod, so a service the default `up` never started meant
`post.liked`, `post.saved`, `post.viewed` and `comment.created` retried and then dead-lettered.
The overlay is gone; there is one way to start the stack.

Storage: data store and cache store both point at the dedicated `gorse` database inside the shared Postgres container.
The `gorse` database is created by `docker/postgres/init/01-create-gorse-db.sql`, which runs only on first boot of an empty volume.
On a volume created before that script existed the container restart-loops on `database "gorse" does not exist`; the container carries `restart: unless-stopped`, so this presents as a service that never becomes healthy rather than as a visible crash.
Create it by hand once: `docker compose exec postgres psql -U "$POSTGRES_USER" -c 'CREATE DATABASE gorse'`.
The healthcheck probes `/api/health/ready`, which reports data-store and cache-store connectivity, so this state now shows up as an unhealthy service instead of a silently absent one.

Required env vars (see `.env.example`): `GORSE_API_KEY`, `GORSE_DASHBOARD_USER`, `GORSE_DASHBOARD_PASSWORD`, `APP_GORSE_BASE_URL`.

Dashboard: `http://localhost:8088` (loopback-only binding; on the VPS use `ssh -L 8088:localhost:8088 <vps>`).

## Verified API contract (v0.5.11, verified 2026-07-26 against the running binary)

Everything below was confirmed with curl against `zhenghaoz/gorse-in-one:0.5.11`.
Do not trust older blog posts or docs; several of these are breaking changes.

| Fact | Verified result |
|---|---|
| Ports | REST API and dashboard share `http_port` 8088 in gorse-in-one; gRPC on 8086 |
| Interactive API docs | `GET /apidocs/` (307 redirect from `/apidocs`) |
| Auth | `X-API-Key` header; `GORSE_SERVER_API_KEY` env var works; 401 without key |
| Dashboard auth | Enabled via `GORSE_DASHBOARD_USER_NAME` / `GORSE_DASHBOARD_PASSWORD` env; `/` redirects to `/login` |
| Insert feedback | `POST /api/feedback` with `[{FeedbackType, UserId, ItemId, Timestamp}]`; idempotent upsert (re-posting the same tuple returns `RowAffected: 1`, no duplicate) |
| Insert items | `POST /api/items` batch with `{ItemId, IsHidden, Categories, Labels, Timestamp, Comment}` |
| Insert users | `POST /api/user` / `POST /api/users`; `auto_insert_user = true` also creates users on first feedback |
| Hide item | `PATCH /api/item/{id}` body `{"IsHidden": true}`; hidden items disappear from `latest`/recommend immediately |
| Recommend | `GET /api/recommend/{userId}?n=&offset=`; plain response is a bare id array; with header `X-API-Version: 2` response is `[{Id, Score}]` - the Java client always sends that header |
| Cold-start | Unknown user falls through to `[recommend.fallback].recommenders` automatically (verified: returns `latest` items) |
| Popular | `GET /api/popular` was REMOVED in v0.5.x (404). Use `GET /api/non-personalized/popular?n=&offset=` backed by the `[[recommend.non-personalized]]` block named `popular` |
| Latest | `GET /api/latest?n=&offset=` returns `[{Id, Score}]` (score = item timestamp epoch) |
| Write-back | `write-back-type`/`write-back-delay` params on recommend are accepted; deliberately NOT used (would mutate state on read and destabilize pagination) |

## v0.5.11 config gotchas (each one broke startup or a call during setup)

1. `[recommend.ranker] type = "none"` requires `recommenders` to contain at most ONE entry.
Multi-source candidate merging needs the FM ranker; cold users are served by `[recommend.fallback]` instead.
2. `--cache-path` is a FOLDER in v0.5.11 (default `/var/lib/gorse/master`, holds an internal SQLite meta db).
Passing a file path fails startup with `unable to open database file: out of memory (14)`.
3. A Redis `cache_store` requires the RediSearch module (`FT.*` commands) - plain `redis:7-alpine` fails with `ERR unknown command 'FT._LIST'`.
This deployment uses Postgres for the cache store instead of adding a Redis Stack container.
4. `negative_feedback_types` is accepted by v0.5.11 under `[recommend.data_source]`, next to `positive_feedback_types` and `read_feedback_types`.
An earlier version of this file said the key did not exist; that was wrong.
The behaviour of the exact image `zhenghaoz/gorse-in-one:0.5.11` was observed on 2026-10-10 in throwaway containers whose config set `negative_feedback_types = ["not_interested"]`:
   - The startup log reports `load dataset ... negative_feedback_types:["not_interested"]`, so the validator takes the key.
   - After a model fit, an item the user had marked with a `not_interested` feedback was absent from `GET /api/recommend/{user}` for that user.
   - `DELETE /api/feedback/not_interested/{user}/{item}` answered `200` with `{"RowAffected":1}`, and the item came back in that user's list after the next ranking pass.
   The exclusion applies to Gorse's personalized list only: the trending top-up and the popularity and chronological fallbacks do not pass through it, so the application filter in `RecommendationFeedServiceImpl` stays the authority.
   Re-run the check by starting a throwaway container from the same image tag with `negative_feedback_types = ["not_interested"]`, posting one feedback row with `POST /api/feedback`, waiting for a fit, and reading `GET /api/recommend/{user}` before and after the `DELETE` above.

## Feedback type mapping (app -> Gorse)

| App event | FeedbackType | Class |
|---|---|---|
| `post.liked.v1` | `like` | positive |
| `post.saved.v1` | `save` | positive |
| `comment.created.v1` | `comment` | positive |
| `post.viewed.v1` | `read` | read |
| `post.shared.v1` | `share` | positive |
| `post.not-interested.v1` | `not_interested` | negative; sent with `POST /api/feedback`, taken back with `DELETE /api/feedback/not_interested/{user}/{item}` on `post.not-interested-undone.v1` |

## Seeding

`gorse/seed/seed.py` generates synthetic persona data: SQL for the app database plus a batch push into Gorse.
The Gorse push phase doubles as the backfill/rebuild tool: Gorse state is always re-derivable from the canonical `user_events` table plus the posts table.
