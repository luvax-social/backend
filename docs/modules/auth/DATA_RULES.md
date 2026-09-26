# Auth Module — Data Rules

**Implementation status**: Fully implemented (`AuthController`, `AuthServiceImpl`, `TokenServiceImpl`, repositories, entities).

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `users` | `id`, `username`, `email`, `role`, `status`, `is_private`, `is_verified`, `registration_ip`, `last_login_ip`, `last_login_at` | Core user identity. Canonical for all user-referencing modules. Soft-deleted via `deleted_at`. `registration_ip` is written once, in the same transaction as the row insert, on both the local and the OAuth creation path. `last_login_ip` and `last_login_at` advance on session issuance only and are deliberately not advanced by a token refresh, so `last_login_at` stays a sign-in signal rather than an activity signal. All three come from `IpExtractor`, which honours `X-Forwarded-For` only from a configured trusted proxy. |
| `user_credentials` | `user_id` (PK/FK), `password_hash`, `email_verified`, `email_verified_at` | Local auth credentials. `password_hash` is nullable — OAuth-only users have no local password. |
| `oauth_accounts` | `id`, `user_id`, `provider`, `provider_id` | Canonical record that a user authenticated via an external OAuth provider. One row per (provider, provider_id) pair. |
| `refresh_tokens` | `id`, `user_id`, `token_hash`, `expires_at`, `revoked_at` | Durable record of issued refresh tokens. Token itself is never stored — only its bcrypt hash. |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `users.follower_count` | `users` table | `follows` join table | Trigger `trg_follow_counts` |
| `users.following_count` | `users` table | `follows` join table | Trigger `trg_follow_counts` |
| `users.post_count` | `users` table | `posts` where `status='published'` and `deleted_at IS NULL` | Trigger `trg_post_count` |
| `users.updated_at` | `users` table | N/A — auto-maintained | Trigger `trg_users_updated_at` |
| Email verification token | Redis | Cannot rebuild — must re-send | TTL-based expiry in Redis |
| Password reset token | Redis | Cannot rebuild — must re-send | TTL-based expiry in Redis |
| Token blacklist entries | Redis | Cannot rebuild — revoke all active tokens as failsafe | TTL tied to JWT access token lifetime |
| Rate-limit counters | Redis | Rebuild by resetting (no data loss consequence) | Request arrival |
| OAuth2 exchange code | Redis (`auth:oauth2:exchange:{code}`) | Cannot rebuild — must re-initiate OAuth2 flow | TTL 120 s |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `username` must be unique across all non-deleted users | `UNIQUE` constraint on `users.username` |
| `email` must be unique across all users | `UNIQUE` constraint on `users.email` |
| `role` defaults to `'user'`; must be one of `user_role` enum | `DEFAULT 'user'`, enum type |
| `status` defaults to `'active'`; must be one of `user_status` enum | `DEFAULT 'active'`, enum type |
| `is_private` and `is_verified` default to `FALSE` | Column `DEFAULT FALSE` |
| `user_credentials.password_hash` is nullable | Column definition allows `NULL` |
| `oauth_accounts` pair `(provider, provider_id)` must be globally unique | `UNIQUE (provider, provider_id)` |
| `refresh_tokens.token_hash` must be globally unique | `UNIQUE` constraint |
| Deleting a user cascades to `user_credentials`, `oauth_accounts`, `refresh_tokens` | `ON DELETE CASCADE` on all FK references |
| Counters (`follower_count`, `following_count`, `post_count`) are non-negative | `CHECK (column >= 0)` on each column |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Registration returns a single generic conflict code (`USER_ALREADY_EXISTS`) for both email and username collisions to prevent account enumeration | `AuthServiceImpl` |
| Passwords are bcrypt-hashed before storage; plaintext is never stored | `AuthServiceImpl` |
| JWT access tokens are stateless (not stored in DB); only refresh token hash is stored | `TokenServiceImpl` |
| Revoked refresh tokens have `revoked_at` set to `NOW()` — they are not deleted | `TokenServiceImpl.revokeRefreshToken()` |
| Email verification flow: record outbox event in the auth transaction → mail consumer generates token → store in Redis with TTL → send email link → verify on click | `AuthServiceImpl`, `AuthMailEventServiceImpl`, `TokenServiceImpl` |
| Password reset flow: record outbox event after account lookup → mail consumer generates token → store in Redis with TTL → send email link → verify → update `password_hash` | `AuthServiceImpl`, `AuthMailEventServiceImpl`, `TokenServiceImpl` |
| Auth mail event consumption is at-least-once: consumer validates the event envelope, deduplicates via `processed_messages`, generates Redis tokens only inside the consumer, sends mail synchronously, then acknowledges the RabbitMQ message. The dedupe marker is retained for 14 days (`app.retention.processed-messages`), longer than any possible redelivery, before a scheduled job purges it | `AuthMailEventConsumer`, `AuthMailEventHandler`, `ProcessedMessageServiceImpl` |
| Invalid auth mail event payloads are treated as permanent failures and routed to `mail.dlq`; temporary mail/Redis/DB failures use bounded retry before DLQ | `AuthMailEventConsumer` |
| OAuth flow: look up `oauth_accounts` by `(provider, provider_id)`; create `users` + `user_credentials` + `oauth_account` row on first login | `CustomOidcUserService`, `OAuth2AuthenticationSuccessHandler` |
| OAuth2 exchange flow: on success, generate a 32-byte hex exchange code, store it in Redis (`auth:oauth2:exchange:{code}`, TTL 120 s, value = userId), redirect browser to `{frontendBaseUrl}/oauth2/callback?code={code}`; the exchange endpoint atomically consumes the code (GET-then-DEL Lua script) and issues a token pair | `OAuth2AuthenticationSuccessHandler`, `OAuth2ExchangeCodeServiceImpl`, `AuthServiceImpl.exchangeOAuth2Code` |
| OAuth2 exchange codes are one-time use; the atomic Lua consume script prevents concurrent redemption from succeeding twice | `OAuth2ExchangeCodeServiceImpl` |
| Revoked / expired access tokens are blacklisted in Redis for the remainder of their TTL | `TokenBlacklistServiceImpl` |
| All auth endpoints are rate-limited via Redis sliding-window counters | `AuthRateLimitFilter`, `RateLimiterServiceImpl` |
| Forgot-password response timing uses a configurable minimum duration after durable event recording to reduce account enumeration signal | `AuthServiceImpl`, `ForgotPasswordTimingEqualizer` |
| A suspended or banned (`status != 'active'`) user is rejected at authentication | `AuthServiceImpl` |
| Soft-deleted users (`deleted_at IS NOT NULL`) cannot authenticate | `AuthServiceImpl` |

### C. Field Clarifications

**`users.is_verified` vs `user_credentials.email_verified`**:
- `user_credentials.email_verified` — confirms the user owns the email address. Set to `true` after the user clicks the email verification link. This is an authentication concern.
- `users.is_verified` — indicates the account has a verified identity badge (e.g., public figure, brand). This is a trust/display concern, set by administrators.
- These two fields are independent. A user can have `email_verified = true` and `is_verified = false`, and vice versa.

### D. Scope Simplifications

- Email verification is enforced on login for local auth users but not yet blocking for OAuth users.
- Device-level `device_id`, `user_agent`, and `ip_address` are stored on `refresh_tokens` for audit, but no active device-management UI exists yet.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` (self) | inbound | All other modules reference `users.id`; auth module owns the `users` table |
| `mail` | outbound async | Auth records transactional outbox events; the mail consumer owns token generation for outbound verification/reset links |
| `social` | none | Social graph is a separate module; auth has no direct dependency |

---

## Known Security Gaps

_No open gaps in this module._
