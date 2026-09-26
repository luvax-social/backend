# Contributing

## Ways to contribute

- **Bug reports** — open a bug report issue using the provided template.
- **Feature requests** — open a feature request issue using the provided template.
- **Code** — implement features or fixes via a pull request.
- **Documentation** — improve `README.md`, module `DATA_RULES.md` files, or inline Javadoc.
- **Tests** — add unit or integration tests to increase coverage.

## Development setup

**Prerequisites**

- Java 21
- Maven (or use the included `./mvnw` wrapper)
- Docker (required for PostgreSQL, Redis, and RabbitMQ)

**Start the local environment**

```bash
docker compose up -d
./mvnw spring-boot:run
```

The application starts with the `dev` profile. Swagger UI is available at `http://localhost:8080/swagger-ui`.

### Local email

Resend is the only mail transport; there is no local sink.
The `dev` profile sends every outbound email through the real Resend provider, exactly as `prod` does.
A valid `RESEND_API_KEY` in `.env` is required to run the application at all - `ResendMailConfig` refuses to start without one.

Registration is only half of creating an account: login stays blocked until the address is verified, and the verification link exists nowhere except inside the outbound email.

1. Register an account through `POST /api/v1/auth/register`, using a real mailbox you control.
2. Open the "Verify your email address" message in that mailbox.
3. Follow the verification link in the message.
4. Log in.

Password reset works the same way: request it, then pick the message up in the same mailbox.

There is no recipient allowlist: every outbound message goes to the real provider, including one triggered against a seeded dataset's fake address.
That consumes Resend send quota, and a bounce from a fake or invalid address raises the sending domain's bounce rate, which can get the domain suspended - a more severe outcome than quota exhaustion.
**Only trigger mail-sending flows (register, forgot-password, and so on) against a real mailbox you control.** Never target a seeded or fabricated address.

To run the application locally without sending real mail - for example while working on something unrelated to auth - set `APP_MAIL_TRANSPORT=noop` in `.env` and restart.
The `noop` transport captures what it would have sent instead of delivering it, and is refused outside the `dev` profile: the application will not start with it selected anywhere else.

### Troubleshooting: startup fails with a Flyway validation error mentioning version 99

Flyway used to apply a dev-only seed script, `V99__seed_feed_test_data.sql`, from `classpath:db/dev-seed`.
That script and its location entry have been removed.
If your local database already recorded that migration as applied, Flyway's validation step will now find a schema history row with no matching migration file and refuse to start the application.

Fix it by removing that one row from your local database, then start the application again:

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DELETE FROM flyway_schema_history WHERE version = '99';"
```

The seed script only ever inserted disposable test rows, so no other cleanup is required.
If you would rather start from a clean database, remove the `postgres` container instead — it has no named volume, so removing it discards its data and Flyway reapplies every migration from scratch on the next `docker compose up -d`:

```bash
docker compose rm -f -s postgres
docker compose up -d postgres
```

## Seeding a fresh environment

A fresh clone gives you a running application with an empty database: no accounts, no content, nothing to log in as.
`scripts/seed-dev-data.sh` creates a small fixed set of accounts and content so you have something to work against.

Bring up the infrastructure and start the application once, so Flyway migrates the schema:

```bash
docker compose up -d
./mvnw spring-boot:run
```

Then, from the repository root, run the seed script:

```bash
bash scripts/seed-dev-data.sh
```

It writes directly to the compose PostgreSQL service through `docker compose exec`, so it needs no `psql` binary on your host and no application container.
It is safe to run repeatedly: every write is guarded by a natural key, so a second run inserts nothing and reports zero rows affected.
It refuses to run unless `POSTGRES_URL` points at localhost and your Docker context is a local socket, so it cannot be aimed at a shared or production database.

You end up with five accounts, all sharing the password `SeedPass123!`:

| Username | Email | Role | Notes |
|----------|-------|------|-------|
| `seed_alice` | `alice@seed.local` | `user` | Public account, two published posts, mutual follow with `seed_carol`, one pending follow request against `seed_bob` |
| `seed_bob` | `bob@seed.local` | `user` | Private account, so follow requests against it stay pending |
| `seed_carol` | `carol@seed.local` | `user` | Public account, one published post |
| `seed_mod` | `mod@seed.local` | `moderator` | For the moderation surfaces |
| `seed_admin` | `admin@seed.local` | `admin` | For the admin surfaces |

Log in with any of them at `POST /api/v1/auth/login`.
The credential field is named `identifier` and accepts either the email or the username:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"alice@seed.local","password":"SeedPass123!"}'
```

The seeded accounts are written pre-verified, so they can be logged into immediately.

The script writes SQL rather than registering through the public API, which is a deliberate choice rather than a workaround.
Registering through the API would work: the local email flow described under [Local email](#local-email) delivers the verification link to a real mailbox, and following it verifies the account.
But that route needs the application, RabbitMQ, and a real Resend credential all working before a single account exists, and it cannot be made idempotent, because a second registration of the same address is a conflict rather than a no-op.
Writing to the database directly needs only the compose PostgreSQL service, which is the one thing that must be up regardless.

Register your own account through the API when you want to exercise the real signup path.
Use the seeded accounts when you just want something to log in as.
Neither route requires a hand-written `UPDATE user_credentials SET email_verified = TRUE`, and that is not a supported procedure.

## Branch naming convention

```
<type>/<scope>/<short-description>
```

| Segment | Values |
|---------|--------|
| type | `feat`, `fix`, `chore` |
| scope | module name (`auth`, `post`, `social`, …) or `common`, `db`, `ci` |
| short-description | lowercase, hyphen-separated words |

Examples:

```
feat/post/add-carousel-support
fix/auth/refresh-token-expiry
chore/db/add-story-indexes
```

## Commit message standard

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>
```

| Type | When to use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code change with no behavior change |
| `test` | Adding or updating tests |
| `chore` | Build, tooling, dependency updates |
| `docs` | Documentation only |
| `perf` | Performance improvement |
| `ci` | CI/CD configuration |

Scope must be a module name or `common`, `db`, `ci`. PR titles are linted by the `pr-lint` workflow and must conform to this format.

Examples:

```
feat(post): add carousel media support
fix(auth): prevent concurrent refresh token consumption
chore(db): add GIN index on hashtag_name for trigram search
refactor(common): extract token blacklist TTL calculation
```

## Pull request process

1. Create a branch following the naming convention above.
2. Implement the change with tests.
3. Run `./mvnw spotless:apply && ./mvnw test` and confirm both pass.
4. Complete every item in the author checklist in `.github/pull_request_template.md`.
5. Open a PR against `main`. The PR title must conform to Conventional Commits — the `pr-lint` workflow enforces this.
6. CODEOWNERS assigns reviewers automatically based on the files changed. At least one assigned reviewer must approve before merge.
7. The `pr-size` workflow labels a PR by size and does not block on it (`fail_if_xl: false`);
   above 2000 changed lines it adds `size/XL` and a warning comment. Keep a feature PR under
   `size/M` (1000 changed lines) as a target and split it yourself when it grows past that.

## Code style

Google Java Format (AOSP variant) enforced by Spotless. Before committing:

```bash
./mvnw spotless:apply
```

Import order: `java`, `jakarta`, `org`, `com`. Tab indent: 4 spaces. The pre-commit hook (`hooks/pre-commit-lint.sh`) blocks commits that violate comment style rules.

## Project-specific rules

These rules differ from generic Java conventions and are strictly enforced during review:

1. **`@Transactional` on Service impl methods only.** Never place it on interfaces or Controller classes.
2. **Denormalized counters are trigger-maintained.** Never write directly to `follower_count`, `following_count`, `post_count`, `like_count`, `comment_count`, `save_count`, `view_count`, or `reply_count` from application code.
3. **All responses must use `ApiResponse<T>`.** Paginated responses use `PageResponse<T>` (offset) or `CursorPageResponse<T>` (cursor).
4. **Controllers delegate to Service only.** No business logic, no repository calls, no direct entity manipulation in the Controller layer.
5. **Comments in English only.** No other language in source files.

## Running tests

Docker must be running — Testcontainers starts PostgreSQL automatically for integration tests.

```bash
./mvnw test
```

## Reporting security issues

See [SECURITY.md](SECURITY.md). Do not open a public issue for security vulnerabilities.
