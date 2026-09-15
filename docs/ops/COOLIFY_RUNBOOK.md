# Coolify Production Runbook

This document exists because production infrastructure was set up and debugged directly in the
Coolify dashboard and on the server terminal, and none of that work was captured anywhere in the
repository.
It is written for a reader who has never opened this Coolify instance.
Every command below is one that was actually run during that session, not a reconstruction.

The single fact everything downstream depends on: **this deployment is not the local
`docker-compose.yaml` stack**.
The repository's compose file models backend, Postgres, Redis, RabbitMQ, Elasticsearch, and Gorse
as one Compose project for local development.
In Coolify, every one of those except Gorse is a separate native resource on Coolify's own shared
network, with its own container naming scheme, and Gorse is the only service still running as
Docker Compose.
Hostnames, health-check behavior, and troubleshooting steps that hold for local dev do not
transfer here without translation.
Sections 2 and 3 below spell out exactly where the two topologies diverge.

Nothing in this document is a substitute for reading the current state of the live Coolify
dashboard.
Resource UUIDs, container names, and image versions change over the life of the deployment; the
commands here show the pattern to follow, not values to paste blindly.

---

## 1. Git repository connection

Coolify's GitHub App source is named `zentech-coolify`, installed under the `zentech-graduation`
organization, scoped to specific repositories rather than the whole org, with `Content: read`,
`Metadata: read`, and `Pull Request: write` permissions.

The backend and frontend are two separate git submodules under a root aggregator repository.
Coolify does not run `git submodule update --init --recursive` on its own, so connecting the
aggregator repo as a build source would deploy an empty checkout of both submodule directories.

**The working pattern**: connect each submodule's own repository — `backend` and `frontend` — as
its own Coolify Application resource, built directly from that repository's `main` branch.
The aggregator repository is not used as a Coolify build source in this setup at all.

---

## 2. Infrastructure topology

Every resource below is a separate native Coolify resource.
This is not a single Docker Compose stack, even though the repository's own `docker-compose.yaml`
models all of it as one compose file for local development.

| Resource (Coolify) | Type | Notes |
|---|---|---|
| `app:main-...` | Application | Spring Boot backend, built from the Dockerfile in the backend repository |
| `postgresql-prod` | Database (native Postgres service) | One Postgres instance serving two separate databases: `luvax` (the application schema) and `gorse` (Gorse's own data/cache store) |
| `redis-prod` | Database (native Redis service) | The application's cache/session/rate-limit store — not shared with Gorse |
| `rabbitmq-prod` | Service | Native RabbitMQ |
| `elasticsearch-prod` | Service | Native Elasticsearch, currently pinned to `9.2.5` — see the version-mismatch incident in Section 4 |
| `service-...` (Gorse) | Docker Compose Service | The only Compose-type resource in the deployment; everything above is Application, Database, or Service |

### 2.1 Networking

Verified empirically with `docker inspect` against the running containers.

Application and Database/Service resource types are automatically attached to a shared Docker
network named `coolify`.
**Docker Compose Service resources are not** — each one gets its own isolated per-resource network
on creation, and must have **"Connect To Predefined Network"** explicitly enabled (Service Stack →
General tab) before it can reach anything else on `coolify`.

This was the root cause of two separate incidents: Gorse's initial `server misbehaving` DNS
failures against Postgres, and Elasticsearch losing backend connectivity after an image upgrade
reset the same checkbox.
The checkbox is not guaranteed to persist across every edit path.

**Standing rule**: any time a Compose-type resource is edited and redeployed, re-verify "Connect To
Predefined Network" is still enabled before assuming a connectivity failure is anything else.

### 2.2 Container names

Application-type resources get a container name with a deploy-timestamp suffix that changes on
every deploy, for example `j10wxypj7n7g235qn9ukgsqz-<timestamp>`.
Any runbook command that references such a container by name must look it up fresh first:

```bash
docker ps --format '{{.Names}}' | grep <resource-uuid>
```

Database, Service, and Compose resource container names are stable across restarts and redeploys —
keyed to the resource UUID, for example `e3jysyod2g5cktd3alc82bug` for Postgres and
`elasticsearch-g950i6zcqp3vwpfgzsyvw34k` for Elasticsearch — and only change if the resource itself
is deleted and recreated.

---

## 3. Backend environment variables

The mapping below uses the actual resource internal hostnames as configured on this deployment.
Coolify's "internal URL" for a Database-type resource resolves to the bare container name (for
example `e3jysyod2g5cktd3alc82bug`), never the compose-style short alias (`postgres`) that only
resolves inside a shared Compose stack.
This is the opposite of what the local `docker-compose.yaml` teaches, and it is worth stating
explicitly rather than rediscovering by trial and error.

| Variable | Source |
|---|---|
| `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `postgresql-prod`'s Environment Variables tab — auto-provisioned by Coolify on database creation |
| `REDIS_PASSWORD` | Mirrored manually from `redis-prod`'s auto-provisioned `SPRING_DATA_REDIS_PASSWORD`, because `application.yaml` reads `${REDIS_PASSWORD:}`, not the Spring-prefixed name |
| `ELASTICSEARCH_USERNAME=elastic`, `ELASTICSEARCH_PASSWORD` | From `elasticsearch-prod`'s `ELASTIC_PASSWORD`. xpack security is enabled here, unlike the local `docker-compose.yaml`, which disables it — a genuine environment divergence, not a mistake |
| `ELASTICSEARCH_URIS` | `http://elasticsearch-g950i6zcqp3vwpfgzsyvw34k:9200` — the full container name, not the short alias `elasticsearch`, which only resolves inside Elasticsearch's own isolated Compose network |
| RabbitMQ vars | Already correctly provisioned by Coolify at database creation time — no manual intervention needed |
| `APP_GORSE_BASE_URL`, `GORSE_API_KEY` | Point at the Gorse Compose service — see Section 5 |

`REDIS_PASSWORD` and `SPRING_DATA_REDIS_PASSWORD` currently coexist on this deployment for safety,
and either could be the source of truth depending on Spring's env-vs-YAML precedence.
Worth consolidating later; not urgent.

Using the short Elasticsearch alias produces `Temporary failure in name resolution` from any other
resource on `coolify` — that error is the signature of this specific mistake.

---

## 4. Elasticsearch client/server version mismatch (resolved)

**Symptom**: `_cluster/health` via plain `curl` returned `200 OK`, but any request routed through
the application's Elastic Java client failed with `media_type_header_exception`.
Health-check success and client failure looked identical from the outside — this is a diagnostic
trap, not a connectivity issue, and the two symptoms are not distinguishable from a health check
alone.

**Cause**: the backend's Maven-resolved Elastic Java client is `9.2.8` — Spring Boot 4.0.6's managed
BOM version, not a version pinned explicitly in `pom.xml`.
`elasticsearch-prod` was originally provisioned at server version `8.19.0`.
The 9.x client sends a `compatible-with=9` content-type header that an 8.x server rejects.

**Fix applied**: upgraded both the `elasticsearch` and `kibana` service images in the Compose file
to `9.2.5`, and discarded the old data volume rather than attempting an in-place major-version
upgrade.
This was justified here because the Elasticsearch index is a disposable sync target rebuilt from
PostgreSQL via `PostIndexSeedRunner` / `HashtagIndexSeedRunner`, not a source of truth.
This justification would not hold for a system where Elasticsearch held primary data — discarding
the volume there would be data loss, not a reset.

**General lesson**: any time the Elastic Java client's resolved version changes — a Spring Boot
upgrade, most likely — re-verify it against whatever Elasticsearch server version is actually
running in each environment.
A passing local dev test against a docker-compose-managed, version-matched Elasticsearch will not
catch this; the mismatch only exists between the client version and whatever a given environment's
server happens to be pinned to.

---

## 5. Gorse deployment (Docker Compose Service)

Gorse is the one resource in this deployment still running as Docker Compose, and every gotcha
below cost real debugging time.

**Resource type**: must be created as **Docker Compose**, not **Docker Image**.
Docker Image offers no field for a custom start command (`-c /etc/gorse/config.toml --log-path
...`) and no way to bind-mount a single config file; "Custom Docker Options" only accepts a fixed
whitelist of `docker run` flags, not command overrides.

**Compose file validity**: the YAML pasted into Coolify's "Create a new Service" editor must be a
complete, valid file with a top-level `services:` key.
Pasting just the Gorse service fragment out of the project's own multi-service
`docker-compose.yaml` fails Coolify's validator with `Docker Compose file must contain a "services"
section`.

**Storage**: Gorse's data store and cache store both point at the shared `postgresql-prod`
instance, using a separate database named `gorse` on that same instance — not the application's
Redis.
This mirrors the project's `docker-compose.yaml` design intentionally: Gorse 0.5.x's Redis cache
backend requires the RediSearch module, which the plain `redis:7-alpine` image used elsewhere does
not ship.

The `gorse` database does not exist by default on a Coolify-provisioned Postgres instance.
The local compose setup relies on a docker-entrypoint init script that only runs against an empty
volume on first boot, which is irrelevant here since `postgresql-prod` already had data.
Create it manually, once:

```bash
docker exec <postgres-container> psql -U postgres -c "CREATE DATABASE gorse;"
```

**Compose `volumes:` syntax**: root-level `volumes:` must use mapping syntax:

```yaml
volumes:
  gorse_data:
```

not list syntax (`volumes:\n  - 'gorse_data:/path'`), which is only valid under a *service's own*
`volumes:` key.
List syntax at the root level produces a fatal but unclear `non-string key in volumes: 0` error at
deploy time.

**Config file mounting — the trickiest part**: Coolify's Persistent Storage UI for Docker Compose
services is read-only.
Volumes and files declared in the compose YAML itself are the only way to add them; the UI's own
"Files" tab, which works for Application resources, is inert for a Compose service.

A relative bind-mount path in the compose file
(`./gorse/config/config.toml:/etc/gorse/config.toml:ro`) resolves against the resource's working
directory on the host — `/data/coolify/services/<resource-uuid>/`.
If the file does not already exist there as a file, Docker creates it as an **empty directory**
instead, which crashes Gorse at startup with a config-parse failure that does not clearly say
"wrong type".

**Working fix**: before deploying, create the real config file at that exact host path via the
server terminal.
If a prior failed deploy attempt already left the empty-directory placeholder, remove it first:

```bash
rmdir /data/coolify/services/<resource-uuid>/gorse/config/config.toml
```

then create the real file at that path before redeploying.

**After any redeploy** that touches the image tag or the compose structure, re-verify the "Connect
To Predefined Network" checkbox from Section 2.1 — it can reset.

---

## 6. Seed pipeline on this environment

### 6.1 Why seeding works under `prod` here

All 15 seed classes under `common/seed/` were originally gated `@Profile("dev")`, which silently
prevented seeding under this deployment's `prod` baseline profile: no error, no log line, just no
seed beans registered.
This was changed to `@Profile("seed & (dev | prod)")` on all 15 classes specifically to allow this
graduation-project staging deployment to run the seed pipeline.

This is a deliberate, recorded risk acceptance for a project with no real user data.
**It is not a pattern to carry into any deployment that has real users** — `SeedResetService`
performs a destructive reset, and running it against a database holding real accounts destroys
them. See `src/main/resources/seed/README.md` for the full seed pipeline design.

### 6.2 Running seed safely on this environment

Toggle these four environment variables on the Coolify Application resource before starting a seed
run:

```
SEED_DATA=true
SEED_REQUIRE_LOCAL_DATASOURCE=false
SPRING_PROFILES_ACTIVE=prod,seed
MAIL_CONSUMER_ENABLED=false
```

Why each one matters:

- `SEED_DATA=true` triggers `SeedRunner`. Without `seed` in `SPRING_PROFILES_ACTIVE`, the
  `@PostConstruct` profile check refuses to run at all.
- `SEED_REQUIRE_LOCAL_DATASOURCE=false` is required because `SeedRunner` otherwise refuses to run
  unless the configured JDBC URL resolves to localhost — this deployment's datasource is remote by
  definition.
- `SPRING_PROFILES_ACTIVE=prod,seed` activates `application-seed.yml`, which holds off the
  notification-producing consumers for the duration of the run, the same as it does locally.
- `MAIL_CONSUMER_ENABLED=false` is set as a literal environment variable on this deployment because
  `application-seed.yml`'s intent to disable mail during seeding is overridden by it — Spring's
  environment property source always wins over profile-specific YAML.
  Leaving this unset while seeding sends real Resend emails to fake seeded addresses.

After seeding completes and is verified, revert all four:

```
SEED_DATA=false
SEED_REQUIRE_LOCAL_DATASOURCE=true
SPRING_PROFILES_ACTIVE=prod
MAIL_CONSUMER_ENABLED=true
```

### 6.3 Known gap: Gorse purge is not fully reliable

`SeedResetService.reset()` is supposed to purge Gorse's own tables before reseeding, but on at
least one seed run this left stale rows behind.
Observed: `luvax.posts` count and Gorse's `items` count diverged (722 vs. 1131), and the overlap
between the two id sets was exactly the Postgres count — the extra 409 items were pure orphans left
over from a prior seed cycle.

**Symptom in production**: the "For You" feed became identical to the chronological "Following"
feed, with `RecommendationFeedServiceImpl` logging `Recommendation pipeline produced no candidates
... falling back to the chronological following feed`.

**Manual remediation that worked**:

```bash
docker exec <postgres-container> psql -U postgres -d gorse -c \
  "TRUNCATE items, feedback, users, documents, message, time_series_points, values CASCADE;"
```

followed by a **Restart** of the Gorse resource — not just the truncate.
Gorse 0.5.11 caches its catalogue in memory at boot and does not pick up an out-of-band truncate
without a restart.

This is a known reliability gap in `SeedResetService` worth a source-code follow-up.
That follow-up is out of scope for this document; this section records the symptom and the manual
workaround only.

### 6.4 Known trap: testing before the outbox drains

`SeedRunner` logs a line clarifying that its synchronous portion finishing does not mean the system
is consistent yet:

> `[seed] N outbox_events rows are enqueued as PENDING; the transactional outbox publisher drains
> them asynchronously on its own schedule ... Elasticsearch, Gorse, and notification state are not
> yet consistent with this seed`

With roughly 44,000 outbox rows and the default publisher batch size and delay, a full drain takes
roughly 7-8 minutes.
Testing the "For You" feed before the drain completes produces the **same** "no candidates, falling
back" symptom as Section 6.3's stale-Gorse-data incident, but for a different reason: too little
feedback data has reached Gorse yet, not orphaned data left behind.
Tell the two apart with:

```bash
docker exec <postgres-container> psql -U postgres -d luvax -c \
  "SELECT status, count(*) FROM outbox_events GROUP BY status;"
```

If `PENDING` is still high, wait longer before concluding anything is broken.
If `PENDING` is low or zero and the symptom persists, it is Section 6.3's stale-data case instead.

---

## 7. Security follow-up applied

`postgresql-prod` was initially provisioned with "Make it publicly available" enabled and public
port `5432` exposed to the internet.
This was unnecessary once every consumer — the backend and Gorse — was confirmed reachable over the
shared `coolify` internal network, and it was disabled.

**Standing expectation**: no database resource should have a public port open unless there is a
specific, named reason, for example an external BI tool that must connect directly.

---

## Out of scope

- The `luvax.online` apex-domain DNS question is a separate, unresolved discussion.
  It is not documented here as settled, and nothing in this runbook should be read as resolving it.
- `SeedResetService`'s Gorse-purge reliability gap (Section 6.3) is documented as a symptom and a
  manual workaround only — no code fix was attempted as part of writing this document.
