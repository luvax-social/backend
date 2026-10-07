---
trigger: model_decision
description: Load when working on App (social network). Contains the authoritative project map.
---

# Project Structure — App (Social Network)

## 0. Application Purpose

Instagram-style social network: profiles, follow graph, photo/video/carousel posts, likes/saves, nested comments, 24-hour stories, 1-1 and group DMs, hashtags, ranked feed.

Private accounts enforce pending follow requests. Content moderation uses a report system with an admin audit log. A recommendation subsystem tracks behavioral events for feed ranking.
Behavioral events and platform statistics live in ClickHouse, which also holds a replica of the audit log; PostgreSQL stays the system of record for everything else.

User roles: `user`, `moderator`, `admin`. Architecture: **Modular Monolith**.

---

## 1. Directory Structure

```text
app/
├── .agents/
│   ├── rules/                      # base.md, changelog_rule.md, comment_style.md, doc_first.md,
│   │                               # git_workflow.md, struct.md, testing.md, workspace.md
│   └── skills/                     # Skill definitions
├── .github/
│   ├── workflows/                  # pr-lint.yml (Conventional Commits), pr-size.yml (PR size
│   │                               # labels), sonarcloud.yml (static analysis)
│   ├── ISSUE_TEMPLATE/             # bug_report.yml, feature_request.yml, config.yml
│   ├── CODEOWNERS                  # Repository code ownership
│   └── pull_request_template.md
├── database/
│   └── schema.sql                  # Reference PostgreSQL final-state schema (not applied by Flyway)
├── docker/
│   └── postgres/                   # Dockerfile (postgres:latest + tz alias, preloads pg_stat_statements)
│                                   # and first-boot init SQL (creates the luvax_monitor role)
├── docs/
│   └── modules/
│       ├── GLOBAL_RULES.md         # Cross-module data rules (enum/config table contracts)
│       └── {module}/DATA_RULES.md  # Per-module data access and write rules (15 modules)
├── src/
│   ├── main/
│   │   ├── java/com/app/
│   │   │   ├── common/             # Cross-cutting infrastructure (see §2)
│   │   │   ├── modules/            # 15 domain modules (see §2)
│   │   │   └── Application.java    # @SpringBootApplication @ConfigurationPropertiesScan
│   │   └── resources/
│   │       ├── clickhouse/
│   │       │   └── migration/      # V1-V3 ClickHouse analytics schema scripts (application-owned runner)
│   │       ├── db/migration/       # Flyway V01-V133 SQL migrations
│   │       ├── elasticsearch/
│   │       │   └── settings/       # hashtags.json, posts.json (Elasticsearch index settings)
│   │       ├── resilience/
│   │       │   ├── circuitbreaker/ # resilience4j-dev.yml, resilience4j-prod.yml
│   │       │   ├── ratelimiter/    # resilience4j-dev.yml, resilience4j-prod.yml
│   │       │   └── retry/          # resilience4j-dev.yml, resilience4j-prod.yml
│   │       ├── templates/mail/     # email-verification.html, oauth-account-no-password.html,
│   │       │                       # password-changed.html, password-reset.html, welcome.html,
│   │       │                       # moderation/ (layout.html + 13 notice variants),
│   │       │                       # support/confirm-support-request.html
│   │       ├── application.yaml    # Core config (active profile: dev)
│   │       ├── application-dev.yml # Dev: JPA show-sql, Swagger at /api-docs, relaxed rate limits
│   │       ├── application-prod.yml# Prod: show-sql off, Swagger disabled
│   │       ├── banner.txt
│   │       └── logback-spring.xml  # Rolling file + console logging
│   └── test/
│       └── java/com/app/
│           ├── ApplicationTests.java                                    # Context smoke test
│           ├── common/config/{elasticsearch,rabbit}/                    # Config unit/integration tests
│           ├── common/exception/                                        # AppException, GlobalExceptionHandler tests
│           ├── common/inbox/service/impl/                               # ProcessedMessageServiceImplIT
│           ├── common/mail/{config,service/impl,util}/                  # Mail config/service/renderer tests
│           ├── common/outbox/{repository,service/impl}/                 # Outbox repo IT, publisher/service tests
│           ├── common/response/                                         # ApiResponse tests
│           ├── common/security/{filter,jwt,service/impl,util}/          # Security unit tests
│           └── modules/{admin,auth,comment,hashtag,mail,media,message,notification,post,recommendation,report,social,story,support,users}/  # Module tests (see §2)
├── docker-compose.yaml             # Local dev: PostgreSQL, RabbitMQ, Redis, Elasticsearch, Gorse
├── pom.xml
├── mvnw / mvnw.cmd
├── scripts/                        # regenerate_struct_figures.sh, regenerate_schema_sql.sh,
│                                   # normalise_schema_dump.py, seed and media helpers,
│                                   # rollback/phase2_postgres_rollback.sql
├── AGENTS.md                       # Agent instructions (root-level)
├── CHANGELOG.md
├── CONTRIBUTING.md
├── README.md
├── SECURITY.md
├── .env                            # Local environment variables (gitignored)
└── .env.example                    # Environment variable template
```

---

## 2. Source Code Architecture

### `common/` — Implemented Infrastructure

| Package | Key Classes |
|---------|------------|
| `common/` | `ApiConstants` |
| `common/analytics/` | `ClickHouseOperations` (the only way application code reaches ClickHouse), `ClickHouseErrorTranslator`, `ClickHouseException`, `ClickHouseUnavailableException`, `ClickHouseRequestRejectedException`, `AnalyticsStoreTruncator` (seed reset) |
| `common/analytics/config/` | `AnalyticsProperties`, `ClickHouseDataSourceConfig`, `NetworkTimeoutDataSource`, `PrimaryDatabaseHealthConfig` |
| `common/analytics/impl/` | `ClickHouseOperationsImpl`, `DisabledClickHouseOperations` |
| `common/analytics/ingest/` | `AnalyticsIngestionController`, `AnalyticsListenerIds` |
| `common/analytics/migration/` | `ClickHouseMigrationRunner`, `ClickHouseMigrationScript`, `ClickHouseMigrationException`, `AnalyticsSchemaGate`, `AnalyticsSchemaReadyEvent` |
| `common/analytics/observability/` | `AnalyticsMetrics` |
| `common/base/` | `BaseController` |
| `common/config/app/` | `AppProperties` |
| `common/config/elasticsearch/` | `ElasticsearchConfig`, `ElasticsearchProperties` |
| `common/config/openapi/` | `OpenApiConfig` |
| `common/config/rabbit/` | `RabbitMqPublisherConfig`, `RabbitMqTopologyConfig` |
| `common/config/redis/` | `RedisConfig`, `RateLimitProperties` |
| `common/config/security/` | `SecurityProperties` |
| `common/config/websocket/` | `WebSocketBrokerConfig` |
| `common/enums/` | `ApiErrorCode`, `ApiSuccessCode` |
| `common/exception/` | `ApiException`, `AppException`, `GlobalExceptionHandler` |
| `common/inbox/entity/` | `ProcessedMessage` |
| `common/inbox/enums/` | `ProcessedMessageResult` |
| `common/inbox/repository/` | `ProcessedMessageRepository`, `ProcessedMessageRepositoryCustom`, `ProcessedMessageRepositoryImpl` |
| `common/inbox/service/` | `ProcessedMessageService` |
| `common/inbox/service/impl/` | `ProcessedMessageServiceImpl` |
| `common/inbox/observability/` | `InboxMetrics` |
| `common/messaging/` | `DeadLetterPublisher`, `DomainEventMessageParser` |
| `common/messaging/config/` | `ConsumerRetryProperties`, `MessagingConsumerConfig` |
| `common/messaging/exception/` | `PermanentMessageException` |
| `common/observability/` | `NoiseObservationPredicate`, `ObservabilityConfig`, `OpenTelemetryAppenderInitializer`, `W3cTraceContext`, `TraceContextCapture` |
| `common/outbox/config/` | `OutboxPublisherConfig`, `OutboxPublisherProperties` |
| `common/outbox/entity/` | `OutboxEvent` |
| `common/outbox/enums/` | `OutboxEventStatus` |
| `common/outbox/exception/` | `OutboxPublishException` |
| `common/outbox/model/` | `DomainEventEnvelope`, `DomainEventEnvelopeJson` |
| `common/outbox/observability/` | `OutboxMetrics`, `OutboxMetricsSampler` |
| `common/outbox/repository/` | `OutboxEventRepository`, `OutboxEventRepositoryCustom`, `OutboxEventRepositoryImpl` |
| `common/outbox/service/` | `OutboxService`, `OutboxPublisherService`, `OutboxPublisherStateService` |
| `common/outbox/service/impl/` | `OutboxServiceImpl`, `OutboxPublisherServiceImpl`, `OutboxPublisherStateServiceImpl`, `OutboxTraceRelay` |
| `common/response/` | `ApiResponse<T>`, `CursorPageResponse<T>`, `PageResponse<T>` |
| `common/retention/` | `RetentionProperties`, `RetentionPurgeJob`, `RetentionWindowValidator` |
| `common/retention/service/` | `RetentionPurgeService` |
| `common/retention/service/impl/` | `RetentionPurgeServiceImpl` |
| `common/security/config/` | `CorsProperties`, `SecurityConfig`, `ManagementSecurityConfig`, `ManagementServerRequestMatcher` |
| `common/security/filter/` | `AuthRateLimitFilter`, `JwtAuthenticationFilter` |
| `common/security/jwt/` | `JwtClaims`, `JwtProperties`, `JwtTokenProvider` |
| `common/security/service/` | `RateLimiterService`, `RefreshTokenService`, `TokenBlacklistService` |
| `common/security/service/impl/` | `RateLimiterServiceImpl`, `RefreshTokenServiceImpl`, `TokenBlacklistServiceImpl` |
| `common/security/user/` | `SecurityMapper`, `UserPrincipal` |
| `common/security/util/` | `CachedBodyHttpServletRequest`, `IpExtractor`, `SecurityUtils` |
| `common/security/websocket/` | `JwtHandshakeInterceptor`, `WebSocketSessionRegistry`, `SessionTrackingWebSocketHandlerDecoratorFactory`, `WebSocketRevocationSweepService` |
| `common/seed/` | Dev-database seed pipeline (`app.seed.*`, dev profile only, `SEED_DATA=true`-gated). `SeedRunner` and `SeedProperties` sit at the package root; `loader/` (`SeedDataLoader`, `SeedContent`) loads and validates the JSON content; `model/` holds the 20 content record types (`UserSeed`, `PersonaSeed`, `PostSeed`, `HashtagSeed`, `CommentPoolSeed`, `CommentChainSeed`, `ConversationSeed`, `MessageSeed`, `ModerationCaseSeed`, `MediaManifestEntry`, and others) that bind `src/main/resources/seed/*.json`; `time/SeedTimeline` and `reset/SeedResetService` are the remaining infrastructure; `writer/` holds the 13 domain writers (`UserSeedWriter`, `MediaSeedWriter`, `SocialGraphSeedWriter`, `PostSeedWriter`, `CommentSeedWriter`, `EngagementSeedWriter`, `StorySeedWriter`, `MessageSeedWriter`, `NotificationSeedWriter`, `ModerationSeedWriter`, `SupportSeedWriter`, `VerificationSeedWriter`, `AnalyticsSeedWriter`); `outbox/` (`SeedOutboxEmitter`, `SeedOutboxBatchWriter`) replays seeded activity through the real transactional outbox. See `src/main/resources/seed/README.md`. |
| `common/settings/repository/` | `SystemSettingRepository` |
| `common/settings/service/` | `SystemSettingService` |
| `common/settings/service/impl/` | `SystemSettingServiceImpl` |
| `common/vocabulary/` | Read-only display-vocabulary surface for the API's closed enum sets - report reasons, notification types, and moderation action types - backed by their config tables (`report_reason_configs`, `notification_type_configs`, `moderation_action_configs`) and including disabled rows. `VocabularyController` → `VocabularyServiceImpl` → `VocabularyRepository`, returning a `VocabularyResponse` composed of `ReportReasonVocabularyResponse`, `NotificationTypeVocabularyResponse`, and `ModerationActionVocabularyResponse`. |

### Feature Module Layer Pattern

```text
{module}/
├── api/          # @RequestMapping interface contracts (optional)
├── config/       # Module-specific @ConfigurationProperties (optional)
├── consumer/     # RabbitMQ @RabbitListener consumers (optional)
├── controller/   # REST endpoints — delegates to Service only
├── converter/    # Spring Converter<S,T> implementations
├── dto/          # Request/Response objects (request/, response/ sub-packages)
├── entity/       # JPA @Entity classes
├── enums/        # Module-scoped enumerations
├── event/        # Domain event payload classes (optional)
├── exception/    # Module-specific exceptions (optional)
├── mapper/       # Entity ↔ DTO conversion (MapStruct interfaces)
├── messaging/    # RabbitMQ binding configs and event-type constants (optional)
├── repository/   # Spring Data JPA interfaces (+ custom impl as needed)
├── runner/       # ApplicationRunner beans for seeding/init (optional)
├── search/       # Elasticsearch @Document classes and search repositories (optional)
├── service/      # Interface + impl/ (@Transactional on impl methods only)
├── storage/      # Object storage abstractions (optional)
└── validation/   # Input validation logic and validated record wrappers (optional)
```

Extra sub-packages (e.g. `oauth2/`, `validation/`, `storage/`) follow the same pattern: add as needed per module.

### Feature Module Roster

| Module | Status | Sub-packages |
|--------|--------|--------------|
| `auth` | **Implemented** | api, config, controller, converter, dto/{request,response}, entity, enums, exception, mapper, messaging, oauth2, repository, service/impl, validation |
| `mail` | **Implemented** | config, config/resend, config/noop, converter, entity, enums, repository, service/impl, util |
| `users` | **Implemented** | api, controller, converter, dto/{request,response}, entity, enums, mapper, repository, service/impl |
| `social` | **Implemented** | api, controller, converter, dto/response, entity, enums, mapper, messaging, repository, service/impl |
| `media` | **Implemented** | api, config, controller, converter, dto/{request,response}, entity, enums, mapper, messaging, repository, service/impl, storage, validation |
| `post` | **Implemented** | api, config, consumer, controller, converter, dto/{request,response}, entity, enums, event, live, mapper, messaging, repository, runner, search, service/impl, validation |
| `hashtag` | **Implemented** | api, config, consumer, controller, converter, dto/{request,response}, entity, enums, event, mapper, messaging, repository, runner, search, service/impl |
| `notification` | **Implemented** | api, config, controller, dto/{request,response}, entity, entity/converter, entity/enums, live, messaging, repository, service/impl |
| `comment` | **Implemented** | api, config, consumer, controller, dto/{request,response}, entity, live, mapper, messaging, observability, repository, service/impl, util |
| `story` | **Implemented** | api, consumer, controller, converter, dto/{request,response}, entity, enums, mapper, messaging, repository, service/impl |
| `message` | **Implemented** | api, config, controller, converter, dto/{request,response}, entity, enums, live, mapper, messaging, repository, service/impl |
| `report` | **Implemented** | api, controller, converter, dto/{request,response}, entity, enums, mapper, repository, service/impl |
| `admin` | **Implemented** | api, config, consumer, controller, converter, dto/{request,response}, entity, enums, mapper, messaging, repository, service/impl |
| `recommendation` | **Implemented** | api, client/{dto,impl}, config, consumer, controller, converter, dto/{request,response}, entity, enums, messaging, observability, rebuild/impl, repository, service/impl/feed |
| `support` | **Implemented** | config, controller, converter, dto/{request,response}, entity, enums, mapper, repository, service/impl |

**Module responsibilities:**
- **`auth`**: Login, register, OAuth2 (Google), JWT refresh, password reset, email verification, forgot-password timing equalization, OAuth2 code exchange.
- **`mail`**: Transactional email via Resend SDK; Thymeleaf templates; `MailTemplate` and `ModerationMailTemplate` enums drive template selection; `MailSender` interface abstracts transport. `email_deliveries` (V96) records every send attempt with the provider message id, and `ModerationMailThrottleImpl` bounds moderation mail to five per recipient per hour. See `docs/modules/mail/DATA_RULES.md`. Resend is the sole transport in every profile; a non-network `noop` transport exists only for the test phase (Surefire-pinned) and can be selected locally via `APP_MAIL_TRANSPORT=noop` in `.env`, but `MailTransportGuard` refuses it outside the `dev` profile.
- **`users`**: Public and private user profiles, user settings, role/status management.
- **`social`**: Follow graph (public/private accounts with pending follow), block list, follow-event publishing via outbox.
- **`media`**: Pre-signed Cloudflare R2 upload URLs, media asset lifecycle, MIME/metadata/path validation.
- **`post`**: Post CRUD (image/video/carousel), likes, saves, view recording, post edit history, visibility enforcement, Elasticsearch index sync via outbox.
- **`hashtag`**: Hashtag creation/normalization, trending computation, Elasticsearch index sync via outbox, trigram-search fallback, and the `active`/`banned`/`deleted` lifecycle that governs what every hashtag surface shows and what every post write path accepts. `HashtagLifecycleService` owns the status transitions, the immediate `hashtag_trending` purge, and the status-spanning administrative reads.
- **`notification`**: The activity feed. `NotificationAggregationRepository` writes rows and aggregates likes, story views and follows into windowed groups with their members in `notification_actors`; `NotificationFeedRepository` serves the filtered keyset list, the head and the bounded unseen badge under one visibility predicate; `NotificationSeenStateRepository` keeps the per-user seen and previous watermarks. `NotificationItemAssembler` hydrates rows through the owning modules' preview services. `SocialNotificationConsumer` handles the follow, unfollow, request, approve, reject, block and verification events, `AdminNotificationConsumer` the warning notice, and `NotificationLiveFanoutConsumer` pushes typed envelopes to `/topic/notifications.{userId}`. See `docs/modules/notification/DATA_RULES.md`.
- **`comment`**: Threaded comment CRUD (create with idempotency, edit, soft-delete subtree), likes, moderation, and real-time live fanout via WebSocket (STOMP over SockJS); `CommentNotificationConsumer` handles `comment.created.v1`, `comment.liked.v1` and `comment.unliked.v1` for notifications; `CommentLiveFanoutConsumer` fans out all `comment.*` events to connected WebSocket sessions; `CommentMaintenanceScheduler` performs periodic pruning tasks.
- **`report`**: User-submitted content flag lifecycle (submit, list, triage, status transitions); `ReportServiceImpl` enforces self-report prevention, duplicate suppression, entity existence validation, valid status-machine transitions, and resolution-note requirements for terminal states.
- **`admin`**: Immutable moderation audit log, atomic moderation actions, the warning and strike discipline ladder, report escalation, the report-anchored moderation view of a reported entity, the administrative hashtag registry, the behavioural activity log read surface, and platform statistics; `AdminServiceImpl` handles ban/unban, suspend/unsuspend, post/comment remove/restore, and report resolve/dismiss, and `AdminHashtagServiceImpl` handles hashtag create/ban/unban/delete - each writing an `admin_actions` row and mutating the target entity in the same transaction. `AdminAuthorizationServiceImpl` holds every actor-and-target rule for both status and role changes. `admin_actions` stays in PostgreSQL as the system of record and is replicated to ClickHouse through the outbox: `AdminActionRecorder` enqueues `admin.action.recorded.v1` in the same transaction as the audit row, PostgreSQL triggers enqueue `admin.action.changed.v1` when a foreign-key `SET NULL` rewrites a row, and `AdminActionReplicationConsumer` fetches the current row and writes it with its `row_version`.
`AdminActionListingServiceImpl` serves the audit-log listing from ClickHouse and falls back to PostgreSQL under one cursor contract; lookups by id stay on PostgreSQL.
`StatsCollectionJob` computes one completed bucket at a time in PostgreSQL and `PlatformStatsCollectionServiceImpl` ships it as one `admin.platform-stats.collected.v1` event; `PlatformStatsIngestConsumer` writes it to ClickHouse, and daily figures are computed at query time.
`AdminStatsServiceImpl` and `AdminUserEventServiceImpl` are the administrator-only read paths and answer `503 ANALYTICS_UNAVAILABLE` when ClickHouse is unavailable.
- **`support`**: The support ticket lifecycle, the appeal route a disciplined account reaches without a session, and account verification requests. `SupportTokenServiceImpl` mints the two single-use link families (`support:token:appeal:`, `support:token:confirmation:`) that authorise exactly one ticket against one audit row without ever minting a session; `SupportTicketServiceImpl` enforces the one-open-ticket guard (V107) and the per-client daily cap on the anonymous public form. See `docs/modules/support/DATA_RULES.md`.
- **`recommendation`**: Owns `user_events` and the personalized ranked feed.
`user_events` lives in ClickHouse (`luvax_analytics.user_events`), not PostgreSQL.
The feed is backed by the external Gorse recommender, reached over REST through `GorseClient`; `RecommendationFeedServiceImpl` runs a Source → Hydrator → Filter → Scorer → Selector pipeline with a `gorse` circuit breaker and degrades to the popularity ranking then the chronological feed.
`user_events` has three writers: `UserEventRecorder` produces `session_start`, `search`, and `profile_view` off the request thread with `wait_for_async_insert=0`, dropping rows rather than failing or extending the caller's request; `RecommendationFeedbackConsumer` turns `post.liked.v1`, `post.saved.v1`, `post.viewed.v1`, `comment.created.v1`, `post.shared.v1` and `comment.liked.v1` into idempotent append-only rows and then Gorse feedback, ClickHouse first, and stores the exact Gorse type and value it sent; `UserEventImportConsumer` writes `recommendation.user-event.imported.v1` events (the seed's path) and never sends them to Gorse.
The For You read-set and the hashtag affinity recompute both read ClickHouse.
`rebuild/` holds the operator-triggered Gorse rebuild (`GorseRebuildRunner`, `GorseRebuildServiceImpl`), and `GorsePurger` is the shared Gorse store truncation used by it and by the seed reset.
See `docs/modules/recommendation/README.md`.

### Transactional Outbox / Inbox Pattern

All domain events flow through shared outbox/inbox infrastructure in `common/outbox/` and `common/inbox/`.

**Outbox (producer side)**:
- `OutboxService.enqueue()` (`PROPAGATION.MANDATORY`) persists a `DomainEventEnvelope` into `outbox_events` within the same transaction as the domain write.
- `outbox_events.trace_parent` / `trace_state` (V124) capture the W3C trace context of the request that wrote the event. `OutboxTraceRelay` restores that context as the parent of the publisher's broker send, so the send and every consumer descend from the originating request; the publisher's own batch trace only carries a `Link` back to it, because a batch spans many unrelated requests. A row written outside any trace (seed replay, a job with tracing filtered out) carries neither column and publishes with no parent.
- `OutboxPublisherServiceImpl` runs on a configurable `@Scheduled` fixed delay: claims a batch of `PENDING` rows using `FOR UPDATE SKIP LOCKED`, publishes to RabbitMQ with publisher confirms, then marks each row `PUBLISHED` or schedules retry / marks `DEAD` based on broker confirm outcome.
- `OutboxEventStatus` lifecycle: `PENDING` → `PROCESSING` → `PUBLISHED` | `DEAD`.
- Configuration namespace: `app.outbox.publisher.*` — batch size, max attempts, confirm timeout, processing lease, per-attempt retry backoffs.
- `OutboxMetrics` / `OutboxMetricsSampler` expose the pending/dead row counts and publish outcomes as Prometheus meters.
- `RetentionPurgeJob` (`common/retention/`) purges `PUBLISHED` rows older than `app.retention.outbox-published` (default 7 days) in bounded batches.

**Inbox (consumer side)**:
- `ProcessedMessageService.processOnce(consumerName, eventId, handler)` guards all consumers against duplicate delivery using `INSERT … ON CONFLICT DO NOTHING RETURNING` into `processed_messages`.
- Returns `PROCESSED` on first execution; `DUPLICATE` (skips handler) on replay.
- `InboxMetrics` exposes processed/duplicate counts per consumer as Prometheus meters.
- `RetentionPurgeJob` purges `processed_messages` rows older than `app.retention.processed-messages` (default 14 days).
- `RetentionWindowValidator` refuses application startup unless `processed-messages` exceeds the longest possible redelivery window (outbox republish attempts plus consumer retry backoffs, on the order of a few minutes with default settings) and is at least as long as `outbox-published`; a shorter window could purge a marker while a duplicate delivery is still possible, or leave a purged outbox row with no remaining duplicate guard.

**Consumer retry policy**:
- On transient failure, consumers nack without requeue and schedule a dead-letter via `DeadLetterPublisher`.
- `PermanentMessageException` bypasses retry and routes directly to the DLQ.
- Configuration namespace: `app.messaging.consumer.*` — max attempts, per-attempt backoff durations.

### Test Coverage

Generated by `./scripts/regenerate_struct_figures.sh tests`, which counts what `git ls-files`
reports under `src/test/java` rather than what this table last said; 332 test classes total.
Re-run it and paste the output back here whenever a test class is added, moved or renamed.

| Package | Test Classes |
|---------|-------------|
| `(root)` | `ApplicationTests` |
| `common` | `AnalyticsRollbackScriptIT`, `ApiConstantsSocialTest`, `ApiConstantsUnroutedFieldsTest`, `UpdatedAtSingleWriterIT` |
| `common/analytics` | `AnalyticsHealthIsolationIT`, `AnalyticsStoreTruncatorTest`, `ClickHouseErrorTranslatorTest`, `ClickHouseOperationsIT`, `ProvisioningScriptParityTest` |
| `common/analytics/impl` | `ClickHouseOperationsImplTest` |
| `common/analytics/ingest` | `AnalyticsIngestionControllerTest`, `AnalyticsOutageIT` |
| `common/analytics/migration` | `ClickHouseMigrationRunnerIT`, `ClickHouseMigrationScriptTest` |
| `common/analytics/observability` | `AnalyticsMetricsTest` |
| `common/base` | `BaseControllerTest` |
| `common/config` | `ManagementPortAccessIT`, `ProdProfileConsumerActivationIT`, `RequiredEnvironmentGuardTest` |
| `common/config/elasticsearch` | `ElasticsearchConfigTest`, `ElasticsearchHealthIT` |
| `common/config/openapi` | `OpenApiContractIT` |
| `common/config/rabbit` | `LiveServerQueueExpiryTest`, `RabbitMqTopologyConfigTest`, `RetiredQueueCleanerTest` |
| `common/config/security` | `RefreshCookiePropertiesTest`, `SecurityPropertiesValidationTest` |
| `common/enums` | `ApiErrorCodeMessageTest` |
| `common/exception` | `ApiExceptionTest`, `AppExceptionTest`, `GlobalExceptionHandlerTest`, `HttpNegotiationExceptionHandlersIT`, `MalformedRequestBodyIT` |
| `common/inbox/repository` | `ProcessedMessageRepositoryIT` |
| `common/inbox/service/impl` | `ProcessedMessageServiceImplIT` |
| `common/mail/config` | `MailPropertiesBindingTest`, `MailTransportEnvOverrideTest`, `MailTransportSelectionTest` |
| `common/mail/service/impl` | `MailServiceImplTest`, `NoopMailSenderTest`, `ResendMailSenderTest`, `TemplateMailSenderParityTest` |
| `common/mail/util` | `MailTemplateRendererTest` |
| `common/messaging` | `DeadLetterPublisherTest` |
| `common/observability` | `NoiseObservationPredicateTest`, `TraceContextCaptureTest`, `W3cTraceContextTest` |
| `common/outbox/observability` | `OutboxMetricsSamplerTest` |
| `common/outbox/repository` | `OutboxEventRepositoryIT` |
| `common/outbox/service/impl` | `OutboxLogCorrelationIT`, `OutboxPublisherRabbitMqIT`, `OutboxPublisherServiceImplTest`, `OutboxServiceImplTest`, `OutboxTraceContinuityIT`, `OutboxTraceRelayTest` |
| `common/pagination` | `CursorCodecTest`, `KeysetPageTest`, `OffsetCursorCodecTest`, `OffsetPageableTest`, `TimeCursorsTest` |
| `common/response` | `ApiResponseTest`, `CursorPageResponseTest`, `ViewerRelationshipResponseTest` |
| `common/retention` | `RetentionWindowValidatorTest` |
| `common/retention/service/impl` | `RetentionPurgeServiceImplTest` |
| `common/security/config` | `CorsPropertiesTest` |
| `common/security/filter` | `AuthRateLimitFilterTest`, `JwtAuthenticationFilterTest` |
| `common/security/jwt` | `JwtTokenProviderTest` |
| `common/security/service/impl` | `RateLimiterServiceImplTest`, `RefreshTokenReplayRevocationIT`, `RefreshTokenServiceImplTest`, `TokenBlacklistServiceImplTest`, `TokenPrincipalResolverImplTest` |
| `common/security/user` | `UserPrincipalTest` |
| `common/security/util` | `CachedBodyHttpServletRequestTest`, `IpExtractorTest`, `SecurityUtilsTest` |
| `common/security/websocket` | `BrokerSendGuardRegistrationIT`, `BrokerTopicSendGuardIT`, `JwtHandshakeInterceptorTest`, `WebSocketHandshakeRateLimitIT`, `WebSocketRevocationIT`, `WebSocketRevocationSweepServiceTest` |
| `common/seed` | `CommentAndEngagementSeedWriterIT`, `DomainWritersSeedWriterIT`, `MediaAndSocialGraphSeedWriterIT`, `PostSeedWriterIT`, `SeedAnalyticsDrainIT`, `SeedDataLoaderRealDataTest`, `SeedDataLoaderTest`, `SeedOutboxEmitterIT`, `SeedProfileConsumerOverrideIT`, `SeedResetServiceIT`, `SeedRunnerDatasourceGuardTest`, `SeedRunnerIT`, `SeedTimelineTest`, `SupportSeedWriterIT`, `UserSeedWriterIT` |
| `common/seed/writer` | `AnalyticsSeedWriterTest` |
| `common/settings/service/impl` | `SystemSettingServiceImplTest` |
| `common/text` | `SnippetsTest` |
| `common/turnstile` | `AuthTurnstileGuardTest`, `TurnstilePropertiesValidationTest`, `TurnstileVerifierTest` |
| `common/vocabulary/controller` | `VocabularyControllerIT` |
| `common/web` | `StrictQueryParameterInterceptorTest` |
| `modules/admin/consumer` | `AdminActionReplicationConsumerIT`, `PlatformStatsIngestConsumerIT` |
| `modules/admin/controller` | `AdminActionListingFallbackIT`, `AdminContentControllerIT`, `AdminControllerIT`, `AdminDisciplineControllerIT`, `AdminHashtagControllerIT`, `AdminStatsControllerIT`, `AdminUserControllerIT`, `AdminUserEventControllerIT` |
| `modules/admin/dto/request` | `AdminUpdateHashtagRequestDeserializationTest` |
| `modules/admin/messaging` | `ModerationMailEventHandlerTest` |
| `modules/admin/repository` | `AdminActionClickHouseKeysetRowLossIT`, `AdminActionKeysetRowLossIT`, `AdminActionReplicationTriggerIT`, `AdminActionRepositoryTest`, `AdminContentMediaStatementCountIT`, `PlatformStatsDailySemanticsIT`, `UserWarningRepositoryIT` |
| `modules/admin/service` | `AdminActionRecorderTest`, `StatsBucketsTest` |
| `modules/admin/service/impl` | `AdminAuthorizationServiceImplTest`, `AdminHashtagServiceImplTest`, `AdminServiceImplTest`, `AdminStatsServiceImplTest`, `AdminUserEventServiceImplTest`, `AdminUserServiceImplTest`, `ModerationNoticeServiceImplTest`, `PlatformStatsIT`, `StatsCollectionJobTest`, `SuspensionExpiryServiceImplTest`, `UserDisciplineServiceImplTest` |
| `modules/auth/controller` | `AuthControllerIT`, `PasswordPolicyIT` |
| `modules/auth/converter` | `OAuthProviderConverterTest` |
| `modules/auth/cookie` | `RefreshTokenCookieManagerTest` |
| `modules/auth/dto/request` | `RegisterRequestDeserializationTest`, `ResetPasswordRequestDeserializationTest` |
| `modules/auth/messaging` | `AuthMailEventConsumerRabbitMqIT`, `AuthMailEventConsumerTest`, `AuthMailEventHandlerTest` |
| `modules/auth/oauth2` | `CookieOAuth2AuthorizationRequestRepositoryTest`, `CustomOidcUserServiceTest`, `CustomOidcUserTest`, `OAuth2AuthenticationFailureHandlerTest` |
| `modules/auth/service/impl` | `AuthForgotPasswordEventServiceImplTest`, `AuthMailEventServiceImplTest`, `AuthResendVerificationEventServiceImplTest`, `AuthServiceImplTest`, `AuthServiceTurnstileTest`, `ForgotPasswordTimingEqualizerTest`, `OAuth2ExchangeCodeServiceImplTest`, `RefreshTokenPurgeJobTest`, `TokenServiceImplTest`, `WebSocketTicketServiceImplTest` |
| `modules/auth/validation` | `PasswordPolicyValidatorTest`, `UserStateValidatorTest` |
| `modules/comment/config` | `CommentWebSocketConfigTest` |
| `modules/comment/consumer` | `CommentNotificationConsumerIT`, `CommentNotificationConsumerTest` |
| `modules/comment/controller` | `CommentControllerIT` |
| `modules/comment/live` | `CommentLiveBlockFilterIT`, `CommentStompSendAuthIT`, `CommentWebSocketAccountStatusIT`, `CommentWebSocketHandshakeRejectionIT`, `CommentWebSocketLiveDeliveryIT` |
| `modules/comment/repository` | `CommentEditedAtColumnIT`, `CommentKeysetRowLossIT`, `CommentSubtreeDeleteEquivalenceIT`, `CommentTopLikedQueryIT` |
| `modules/comment/service/impl` | `CommentAuthorEmbeddingIT`, `CommentModerationServiceImplTest`, `CommentPinnedTopCommentsIT`, `CommentPreviewServiceImplTest`, `CommentServiceImplTest`, `CommentViewerStateIT`, `CommentViewerStateServiceImplTest` |
| `modules/hashtag/consumer` | `HashtagIndexSyncConsumerIT`, `HashtagIndexSyncConsumerTest` |
| `modules/hashtag/controller` | `HashtagControllerIT` |
| `modules/hashtag/repository` | `HashtagRepositoryIT`, `TrendingPreviewIT` |
| `modules/hashtag/service/impl` | `HashtagLifecycleServiceImplTest`, `HashtagLookupServiceImplTest`, `HashtagPinLifecycleTest`, `HashtagSearchServiceImplTest`, `HashtagServiceImplTest`, `HashtagTrendingServiceImplTest`, `HashtagTrendingSnapshotIT`, `PersonalisedTrendingServiceImplTest` |
| `modules/mail/service/impl` | `MailRecipientAllowlistTest`, `ModerationMailThrottleImplTest`, `ResendMailSenderTest` |
| `modules/media/controller` | `MediaControllerIT` |
| `modules/media/repository` | `MediaAssetRepositoryIT` |
| `modules/media/service/impl` | `MediaAssetRegistrarTest`, `MediaEventServiceImplTest`, `MediaServiceImplTest` |
| `modules/media/storage` | `MediaStorageKeyGeneratorTest`, `R2ObjectStoragePresignServiceTest` |
| `modules/media/validation` | `MediaMetadataValidatorTest` |
| `modules/message/config` | `MessagePropertiesTest` |
| `modules/message/controller` | `MessageControllerIT` |
| `modules/message/converter` | `MessageTypeConverterTest` |
| `modules/message/live` | `MessageWebSocketAuthInterceptorTest` |
| `modules/message/mapper` | `MessageMapperTest` |
| `modules/message/repository` | `ConversationKeysetRowLossIT` |
| `modules/message/service/impl` | `ConversationServiceImplTest`, `DirectConversationProvisionerIT`, `MessageServiceImplTest` |
| `modules/notification/config` | `NotificationWebSocketConfigTest` |
| `modules/notification/controller` | `NotificationControllerIT` |
| `modules/notification/entity/converter` | `NotificationTypeConverterTest` |
| `modules/notification/live` | `NotificationLiveDeliveryIT`, `NotificationLiveFanoutConsumerTest`, `NotificationOnlyWebSocketConfigIT`, `NotificationPushLatencyIT`, `NotificationWebSocketSubscriptionAuthIT` |
| `modules/notification/messaging` | `AdminNotificationConsumerTest`, `SocialNotificationConsumerIT`, `SocialNotificationConsumerTest` |
| `modules/notification/migration` | `NotificationAdminActionForeignKeyIT`, `NotificationOverhaulMigrationIT` |
| `modules/notification/repository` | `NotificationAggregationRepositoryIT`, `NotificationFeedRepositoryIT`, `NotificationSeenStateRepositoryIT` |
| `modules/notification/service/impl` | `NotificationItemAssemblerTest`, `NotificationServiceImplTest`, `NotificationTypePolicyTest` |
| `modules/post/consumer` | `PostIndexSyncConsumerIT`, `PostIndexSyncConsumerTest`, `PostNotificationConsumerIT`, `PostNotificationConsumerTest` |
| `modules/post/controller` | `PostBannedHashtagIT`, `PostControllerIT` |
| `modules/post/dto/response` | `FeedPostResponseTest` |
| `modules/post/live` | `PostLikeLiveDeliveryIT`, `PostOnlyWebSocketConfigIT` |
| `modules/post/repository` | `PostKeysetRowLossIT` |
| `modules/post/service/impl` | `PostAuthorEmbeddingIT`, `PostByHashtagSearchReaderTest`, `PostByHashtagServiceImplTest`, `PostLikeEventPublishingIT`, `PostLikeServiceImplTest`, `PostPreviewServiceImplTest`, `PostResponseAssemblerTest`, `PostSaveServiceImplTest`, `PostSearchServiceImplTest`, `PostServiceImplTest`, `PostViewServiceImplTest`, `PostViewerStateIT`, `PostViewerStateServiceImplTest`, `PostVisibilityServiceImplTest` |
| `modules/post/validation` | `PostTypeFilterTest` |
| `modules/recommendation/client/impl` | `GorseClientImplTest`, `GorsePurgerConnectionDetailsIT`, `GorsePurgerIT` |
| `modules/recommendation/config` | `RecommendationPropertiesTest` |
| `modules/recommendation/consumer` | `RecommendationFeedbackConsumerTest`, `UserEventImportConsumerIT` |
| `modules/recommendation/controller` | `ImpressionIngestIT`, `RecommendationControllerIT` |
| `modules/recommendation/dto/request` | `ImpressionRequestValidationTest` |
| `modules/recommendation/migration` | `DormantTablesDropIT` |
| `modules/recommendation/rebuild` | `GorseRebuildRunnerTest`, `GorseRebuildServiceIT` |
| `modules/recommendation/rebuild/impl` | `GorseRebuildServiceImplTest` |
| `modules/recommendation/repository` | `AffinityProfileDepthIT`, `HashtagAffinityClickHouseIT`, `SuggestionReadFilterIT`, `UserEventAnalyticsRepositoryIT`, `UserEventKeysetRowLossIT`, `UserHashtagAffinityRepositoryIT` |
| `modules/recommendation/service` | `UserEventRecordingIT` |
| `modules/recommendation/service/impl` | `RecommendationFeedServiceImplTest`, `SuggestionRowMappingTest`, `SuggestionServiceImplTest` |
| `modules/recommendation/service/impl/feed` | `RecommendationSourceTest` |
| `modules/report/controller` | `ReportControllerIT` |
| `modules/report/repository` | `ReportKeysetRowLossIT`, `ReportQueueIndexIT`, `ReportRepositoryIT` |
| `modules/report/service/impl` | `ReportServiceImplTest`, `ReportServiceTurnstileTest`, `ReportedTargetServiceImplTest`, `ReportedViewerStateIT` |
| `modules/social/controller` | `SocialControllerIT` |
| `modules/social/converter` | `FollowStatusConverterTest` |
| `modules/social/repository` | `FollowKeysetRowLossIT`, `FollowRepositoryIT` |
| `modules/social/service/impl` | `BlockedListIT`, `MutualFollowProvisioningIT`, `SocialEventServiceImplTest`, `SocialRelationshipIT`, `SocialServiceImplTest` |
| `modules/story/consumer` | `StoryNotificationConsumerIT`, `StoryNotificationConsumerTest` |
| `modules/story/controller` | `StoryControllerIT` |
| `modules/story/repository` | `StoryDiscoveryIT`, `StoryViewKeysetRowLossIT` |
| `modules/story/service/impl` | `StoryLikeServiceImplTest`, `StoryPreviewServiceImplTest`, `StoryServiceImplTest`, `StoryViewServiceImplTest`, `StoryVisibilityServiceImplTest` |
| `modules/support/repository` | `SupportTicketConcurrencyIT` |
| `modules/support/service/impl` | `AppealRecoveryServiceImplTest`, `AppealStatusMailerImplTest`, `SupportAuthorizationServiceImplTest`, `SupportTicketPreviewServiceImplTest`, `SupportTicketServiceImplTest`, `VerificationServiceImplTest` |
| `modules/users/controller` | `UserControllerIT` |
| `modules/users/mapper` | `UserMapperTest` |
| `modules/users/repository` | `UserRepositorySurfaceTest` |
| `modules/users/service/impl` | `UserProfileViewerStateIT`, `UserSearchIT`, `UserSearchServiceImplTest`, `UserServiceImplTest`, `UserSummaryServiceIT`, `UserSummaryServiceImplTest`, `UsernameLookupIT` |
| `testsupport` | `ClickHouseTestSupport`, `TestContainerImages` |

---

## 3. Infrastructure

### Database

- Engine: **PostgreSQL** (docker-compose builds `./docker/postgres` on the `postgres:latest` base)
- Migration: **Flyway** (`out-of-order: false`); 133 migrations at `src/main/resources/db/migration/`. V57, V63, V66, V68, V71, V72, V73, V74, V81, V82, V91, V100, V103, V107, V109, V110, V121, V122 and V125 build or drop their indexes `CONCURRENTLY` and carry a `.sql.conf` sidecar setting `executeInTransaction=false`; those nineteen sidecars are the only ones in the tree. Regenerate this paragraph and the table below with `./scripts/regenerate_struct_figures.sh migrations`. Every other migration adds no index and runs in the ordinary transactional mode. The numbering has no gaps: V01 through V133 all exist.

| Migration | Description |
|-----------|-------------|
| V01 | create_extensions_and_enums |
| V02 | create_users_auth_tables |
| V03 | create_user_settings_and_push |
| V04 | create_social_graph |
| V05 | create_media_assets |
| V06 | create_post_tables |
| V07 | create_comment_tables |
| V08 | create_hashtag_tables |
| V09 | create_story_tables |
| V10 | create_notification_table |
| V11 | create_message_tables |
| V12 | create_report_table |
| V13 | create_admin_table |
| V14 | create_recommendation_tables |
| V15 | create_indexes |
| V16 | create_triggers_and_functions |
| V17 | create_views |
| V18 | add_metadata_config_tables |
| V19 | create_outbox_events |
| V20 | create_processed_messages |
| V21 | add_outbox_claim_lease_columns |
| V22 | create_post_edit_history |
| V23 | drop_legacy_plaintext_credential_columns |
| V24 | add_refresh_tokens_expires_index |
| V25 | add_text_post_type |
| V26 | add_comment_moderation_status |
| V27 | create_comment_write_idempotency |
| V28 | add_user_events_upcoming_partitions |
| V29 | preserve_admin_action_audit_history |
| V30 | add_reports_duplicate_unique_index |
| V31 | create_message_write_idempotency |
| V32 | preserve_message_sender_history |
| V33 | add_direct_conversation_pair_key |
| V34 | add_keyset_tiebreaker_indexes |
| V35 | add_like_save_keyset_indexes |
| V36 | add_comment_keyset_indexes |
| V37 | add_follow_keyset_indexes |
| V38 | add_story_view_keyset_index |
| V39 | add_notification_keyset_index |
| V40 | add_blocks_keyset_index |
| V41 | add_comment_top_liked_index |
| V42 | add_username_case_insensitive_index |
| V43 | align_username_index_with_soft_delete_policy |
| V44 | add_email_case_insensitive_index |
| V45 | add_comment_edited_at |
| V46 | add_post_likes_user_keyset_index |
| V47 | add_notification_post_id |
| V48 | add_users_banner_url |
| V49 | add_story_likes |
| V50 | remove_group_conversations |
| V51 | order_follow_counter_locks |
| V52 | add_conversation_participant_customization |
| V53 | add_conversation_manual_unread_flag |
| V54 | add_admin_action_type_values |
| V55 | add_moderation_action_configs_rows |
| V56 | add_users_admin_visibility_columns |
| V57 | add_users_admin_visibility_indexes |
| V58 | add_users_token_epoch |
| V59 | add_posts_status_before_moderation |
| V60 | add_notification_type_warning |
| V61 | add_notification_type_configs_warning_row |
| V62 | create_user_discipline_tables |
| V63 | create_user_discipline_indexes |
| V64 | add_report_status_escalated |
| V65 | add_reports_escalation_columns |
| V66 | add_reports_escalated_index |
| V67 | add_hashtag_status |
| V68 | add_hashtag_status_indexes |
| V69 | fill_user_events_partition_gaps |
| V70 | create_platform_stats |
| V71 | add_platform_stats_series_index |
| V72 | add_admin_listing_indexes |
| V73 | add_reports_open_queue_index |
| V74 | add_admin_actions_created_index |
| V75 | add_admin_action_type_content_values |
| V76 | add_moderation_action_configs_content_rows |
| V77 | add_messages_admin_removed_at |
| V78 | backfill_missing_user_settings |
| V79 | add_admin_action_type_revoke_session |
| V80 | add_moderation_action_configs_revoke_session |
| V81 | add_reports_escalated_by_index |
| V82 | add_admin_actions_target_created_index |
| V83 | add_notification_type_post_removed |
| V84 | add_notification_message_and_post_removed_config |
| V85 | add_report_post_removed_notification_type |
| V86 | add_report_post_removed_notification_config |
| V87 | add_post_restored_notification_type |
| V88 | add_post_restored_notification_config |
| V89 | make_report_duplicate_guard_active_only |
| V90 | create_user_hashtag_affinity |
| V91 | add_user_hashtag_affinity_user_score_index |
| V92 | add_hashtags_pin_columns |
| V93 | add_admin_action_type_hashtag_pin |
| V94 | add_moderation_action_configs_hashtag_pin |
| V95 | add_comments_stories_admin_removed_at |
| V96 | create_email_deliveries |
| V97 | create_support_tickets |
| V98 | add_support_enum_values |
| V99 | add_support_config_rows |
| V100 | add_support_indexes |
| V101 | create_mail_campaigns |
| V102 | add_campaign_config_row |
| V103 | add_campaign_indexes |
| V104 | add_verification_enum_values |
| V105 | add_verification_config_rows |
| V106 | create_verification_tables |
| V107 | narrow_one_open_ticket_guard |
| V108 | create_suggestion_tables |
| V109 | add_verification_and_suggestion_indexes |
| V110 | add_user_hashtag_affinity_hashtag_index |
| V111 | add_campaign_recipient_suppressed_status |
| V112 | add_content_removal_notification_types |
| V113 | add_content_removal_notification_configs |
| V114 | remove_mail_campaigns |
| V115 | create_notification_category_type |
| V116 | extend_notifications_for_overhaul |
| V117 | create_notification_actors_and_seen_states |
| V118 | aggregate_existing_notifications |
| V119 | soft_delete_message_notifications |
| V120 | backfill_notification_seen_states |
| V121 | add_notification_feed_indexes |
| V122 | drop_superseded_notification_indexes |
| V123 | drop_notifications_is_read |
| V124 | add_outbox_trace_context |
| V125 | add_outbox_retention_indexes |
| V126 | create_pg_stat_statements_extension |
| V127 | add_admin_actions_replication_version |
| V128 | add_notifications_admin_action_fk |
| V129 | validate_notifications_admin_action_fk |
| V130 | drop_platform_stats |
| V131 | drop_user_events |
| V132 | drop_dormant_recommendation_tables |
| V133 | create_gorse_rebuild_runs |

- Reference schema: `database/schema.sql` (authoritative final-state; not applied by Flyway)
- Extensions: `pgcrypto` (UUID gen), `pg_trgm` (fuzzy username search), `btree_gin` (composite GIN indexes)

PostgreSQL enum types:

Generated by `./scripts/regenerate_struct_figures.sh enums`, and reproduced from
`database/schema.sql`, which is itself regenerated from the migration set. Adding a value
means a migration: these are domain primitives, not configuration.

| Enum | Values |
|------|--------|
| `admin_action_type` | `ban_user`, `unban_user`, `suspend_user`, `unsuspend_user`, `remove_post`, `restore_post`, `remove_comment`, `restore_comment`, `resolve_report`, `dismiss_report`, `change_user_role`, `warn_user`, `revoke_warning`, `issue_strike`, `revoke_strike`, `escalate_report`, `force_logout`, `create_hashtag`, `edit_hashtag`, `ban_hashtag`, `unban_hashtag`, `delete_hashtag`, `remove_story`, `restore_story`, `remove_message`, `restore_message`, `revoke_session`, `pin_hashtag`, `unpin_hashtag`, `respond_support_ticket`, `reject_support_ticket`, `escalate_support_ticket`, `grant_verification`, `reject_verification`, `revoke_verification` (35 values). Every value has a caller. `revoke_session` ends exactly one session, distinct from `force_logout`, which ends every session on the account. |
| `email_delivery_status` | `pending`, `sent`, `failed`, `throttled`, `skipped` (5 values). |
| `follow_status` | `pending`, `accepted` (2 values). |
| `hashtag_status` | `active`, `banned`, `deleted` (3 values). |
| `media_type` | `image`, `video` (2 values). |
| `message_type` | `text`, `image`, `video`, `post_share`, `story_share` (5 values). |
| `notification_category` | `like`, `comment`, `mention`, `follow`, `story`, `message`, `system` (7 values, V115). Derived from `notification_type` in application code; drives the feed filters. `message` is retired with the `message` type: no producer writes it since direct messages left the feed. |
| `notification_type` | `like_post`, `like_comment`, `comment_post`, `reply_comment`, `follow`, `follow_request`, `mention_post`, `mention_comment`, `story_view`, `message`, `warning`, `post_removed`, `report_post_removed`, `post_restored`, `report_dismissed`, `support_ticket_update`, `comment_removed`, `story_removed`, `message_removed` (19 values). |
| `oauth_provider` | `google`, `facebook`, `apple` (3 values). |
| `post_status` | `draft`, `published`, `archived`, `removed` (4 values). |
| `post_type` | `image`, `video`, `carousel`, `text` (4 values). |
| `report_reason` | `spam`, `nudity`, `violence`, `hate_speech`, `harassment`, `false_information`, `scam`, `other` (8 values). |
| `report_status` | `pending`, `reviewing`, `resolved`, `dismissed`, `escalated` (5 values). |
| `report_type` | `post`, `comment`, `user`, `story`, `message` (5 values). |
| `story_type` | `image`, `video` (2 values). |
| `support_category` | `appeal_ban`, `appeal_suspension`, `appeal_warning_strike`, `appeal_content_removal`, `account_access`, `account_data`, `bug_report`, `safety_concern`, `other`, `verification_request` (10 values). |
| `support_source` | `authenticated`, `signed_link`, `public_form` (3 values). |
| `support_ticket_status` | `pending_confirmation`, `open`, `in_progress`, `escalated`, `answered`, `rejected` (6 values). |
| `user_role` | `user`, `moderator`, `admin` (3 values). |
| `user_status` | `active`, `suspended`, `deactivated`, `banned` (4 values). |
| `verification_revocation_actor` | `moderator`, `system` (2 values). |

`event_type` (dropped with `user_events` in V131) and `stat_granularity` (dropped with `platform_stats` in V130) are no longer PostgreSQL types.
The user event types live on as the ClickHouse `Enum8` on `luvax_analytics.user_events.event_type` and as `UserEventType`, and `StatGranularity` is now only the API's `half_hour` or `day` choice.
The regeneration script lists the two dropped types from the migrations that created them; the live set is that union less these two.

### Cache — Redis

- docker-compose: `redis:7-alpine`
- Implemented: `RedisConfig`, `RateLimitProperties`, `TokenBlacklistServiceImpl`, `RefreshTokenServiceImpl`, `RateLimiterServiceImpl`, `OAuth2ExchangeCodeServiceImpl`, `WebSocketTicketServiceImpl`, `CommentPresenceServiceImpl`, `PersonalisedTrendingServiceImpl`, `ModerationMailThrottleImpl`, `SupportTokenServiceImpl`, `SupportTicketServiceImpl` (Lua scripts for atomic ops)
- Key patterns in use:

| Key pattern | TTL | Owner |
|-------------|-----|-------|
| `auth:blacklist:{jti}` | remaining access token lifetime | `TokenBlacklistServiceImpl` |
| `auth:ratelimit:{endpoint}:{ip_or_email}` | sliding window | `RateLimiterServiceImpl` |
| `auth:token:email-verification:{sha256}` | 24h | `TokenServiceImpl` |
| `auth:token:email-verification:user:{userId}` | 24h (reverse index) | `TokenServiceImpl` |
| `auth:token:password-reset:{sha256}` | 15m | `TokenServiceImpl` |
| `auth:token:password-reset:user:{userId}` | 15m (reverse index) | `TokenServiceImpl` |
| `auth:oauth2:exchange:{code}` | 120s | `OAuth2ExchangeCodeServiceImpl` |
| `auth:ratelimit:mail:moderation:{sha256(email)}` | 1h sliding | `ModerationMailThrottleImpl` |
| `support:token:appeal:{sha256}` | 30d | `SupportTokenServiceImpl` |
| `support:token:appeal:subject:{adminActionId}` | 30d (reverse index) | `SupportTokenServiceImpl` |
| `support:token:appeal-status:{sha256}` | 90d | `SupportTokenServiceImpl` |
| `support:token:appeal-status:subject:{ticketId}` | 90d (reverse index) | `SupportTokenServiceImpl` |
| `support:token:confirmation:{sha256}` | 24h | `SupportTokenServiceImpl` |
| `support:token:confirmation:subject:{ticketId}` | 24h (reverse index) | `SupportTokenServiceImpl` |
| `auth:ratelimit:support:public:daily:{email}` | 24h sliding, 10 per address | `SupportTicketServiceImpl` |
| `comment:watchers:{postId}` | 300s | `CommentPresenceServiceImpl` |
| `comment:slowmode:{postId}:{userId}` | the post's slow-mode interval | `CommentServiceImpl` |
| `hashtag:trending:personalised:{userId}:{page}:{size}` | 10m | `PersonalisedTrendingServiceImpl` |
| `auth:ws-ticket:{ticket}` | 30s | `WebSocketTicketServiceImpl` |

`./scripts/regenerate_struct_figures.sh redis` lists every key literal declared under
`src/main/java` and the class that declares it, so a new prefix added without a row here shows up
as a difference. It cannot derive a TTL; read that from the declaring class.

The two support token families are keyed on the audit row and the ticket respectively rather than on
the account, because one account may hold appealable decisions against several actions at once and
issuing the second link must not invalidate the first.
The appeal window is thirty days because the notice arrives unannounced and is routinely read late.

### Message Broker — RabbitMQ

- docker-compose: `rabbitmq:4.3-management` (5672 broker, 15672 management UI, 15692 Prometheus metrics, all bound to loopback). Pinned to 4.3 because `rabbitmq_prometheus` is already enabled in that image, so 15692 only needed publishing.
- Topology declared in `RabbitMqTopologyConfig`; publisher customized in `RabbitMqPublisherConfig`
- `spring.rabbitmq.template.observation-enabled` and `spring.rabbitmq.listener.simple.observation-enabled` are both `true`, so every publish and every listener invocation produces a Micrometer observation and a trace span.

**Exchanges:**

| Exchange | Type | Durable | Role |
|----------|------|---------|------|
| `social.events` | Topic | yes | Primary event bus for all domain events |
| `social.events.dlx` | Topic | yes | Dead-letter exchange for failed messages |
| `comment.live.events` | Fanout | yes | Live comment fanout tier; receives all `comment.*` events via exchange-to-exchange binding from `social.events` |
| `message.live.events` | Fanout | yes | Live message fanout tier; receives all `message.*` events via exchange-to-exchange binding from `social.events` |
| `notification.live.events` | Fanout | yes | Live notification fanout tier; receives all `notification.*` events via exchange-to-exchange binding from `social.events` |
| `post.live.events` | Fanout | yes | Live post fanout tier; receives all `post.live.*` events via exchange-to-exchange binding from `social.events` |

**Queues and DLQs (all durable):**

| Queue | Dead-letter queue | DLQ routing key to `social.events.dlx` |
|-------|------------------|----------------------------------------|
| `mail.queue` | `mail.dlq` | `mail.dead-letter` |
| `moderation.mail.queue` | `moderation.mail.dlq` | `moderation.mail.dead-letter` |
| `notification.queue` | `notification.dlq` | `notification.dead-letter` |
| `hashtag.index.sync` | `hashtag.index.sync.dlq` | `hashtag.index.dead-letter` |
| `post.index.sync` | `post.index.sync.dlq` | `post.index.dead-letter` |
| `comment.notification.queue` | `comment.notification.dlq` | `comment.notification.dead-letter` |
| `story.notification.queue` | `story.notification.dlq` | `story.notification.dead-letter` |
| `recommendation.feedback.queue` | `recommendation.feedback.dlq` | `recommendation.feedback.dead-letter` |
| `post.notification.queue` | `post.notification.dlq` | `post.notification.dead-letter` |
| `admin.notification.queue` | `admin.notification.dlq` | `admin.notification.dead-letter` |
| `admin.action.replication.queue` | `admin.action.replication.dlq` | `admin.action.replication.dead-letter` |
| `admin.platform-stats.queue` | `admin.platform-stats.dlq` | `admin.platform-stats.dead-letter` |
| `recommendation.user-event.import.queue` | `recommendation.user-event.import.dlq` | `recommendation.user-event.import.dead-letter` |

That is 13 queues and their 13 dead-letter queues, 26 durable queues in all.
Seventeen consumer classes each carry one `@RabbitListener` method.
The four analytics listeners (`adminActionReplication`, `platformStatsIngest`, `userEventImport`, `recommendationFeedback`) start with `autoStartup = false` and are started and stopped by `AnalyticsIngestionController`.

`AUDIT_LOG_QUEUE`, `MODERATION_QUEUE` and `SEARCH_INDEX_QUEUE` are name constants only: they are
declared in `RabbitMqTopologyConfig` and reserved, and no `@Bean` declares them, so the broker
never sees them.

**Bindings (queue → `social.events`):**

| Queue | Routing key / pattern | Source config |
|-------|-----------------------|---------------|
| `mail.queue` | `user.registered.v1` | `AuthMailRabbitBindingConfig` |
| `mail.queue` | `auth.email-verification.requested.v1` | `AuthMailRabbitBindingConfig` |
| `mail.queue` | `auth.password-reset.requested.v1` | `AuthMailRabbitBindingConfig` |
| `mail.queue` | `auth.password-changed.v1` | `AuthMailRabbitBindingConfig` |
| `mail.queue` | `auth.oauth-account-no-password.v1` | `AuthMailRabbitBindingConfig` |
| `notification.queue` | `user.followed.v1` | `NotificationRabbitBindingConfig` |
| `notification.queue` | `user.follow-requested.v1` | `NotificationRabbitBindingConfig` |
| `notification.queue` | `user.unfollowed.v1`, `user.follow-request.approved.v1`, `user.follow-request.rejected.v1`, `user.blocked.v1` | `NotificationRabbitBindingConfig` |
| `notification.queue` | `user.verification-changed.v1` | `NotificationRabbitBindingConfig` |
| `hashtag.index.sync` | `hashtag.index.#` (wildcard) | `HashtagRabbitBindingConfig` |
| `post.index.sync` | `post.index.#` (wildcard) | `PostRabbitBindingConfig` |
| `comment.notification.queue` | `comment.created.v1` | `CommentRabbitBindingConfig` |
| `comment.notification.queue` | `comment.liked.v1` | `CommentRabbitBindingConfig` |
| `comment.notification.queue` | `comment.unliked.v1` | `CommentRabbitBindingConfig` |
| `post.notification.queue` | `post.liked.v1`, `post.unliked.v1` | `PostRabbitBindingConfig` |
| `story.notification.queue` | `story.viewed.v1` | `StoryRabbitBindingConfig` |
| `recommendation.feedback.queue` | `post.liked.v1`, `post.saved.v1`, `post.viewed.v1`, `comment.created.v1`, `post.shared.v1`, `comment.liked.v1` | `RecommendationRabbitBindingConfig` |
| `admin.notification.queue` | `user.warned.v1` | `AdminRabbitBindingConfig` |
| `admin.action.replication.queue` | `admin.action.recorded.v1`, `admin.action.changed.v1` | `AdminAnalyticsRabbitBindingConfig` |
| `admin.platform-stats.queue` | `admin.platform-stats.collected.v1` | `AdminAnalyticsRabbitBindingConfig` |
| `recommendation.user-event.import.queue` | `recommendation.user-event.imported.v1` | `RecommendationRabbitBindingConfig` |
| `moderation.mail.queue` | `admin.moderation-notice.requested.v1` | `AdminRabbitBindingConfig` |
| `comment.live.events` (exchange) | `comment.#` (wildcard, exchange-to-exchange) | `RabbitMqTopologyConfig` |
| `message.live.events` (exchange) | `message.#` (wildcard, exchange-to-exchange) | `RabbitMqTopologyConfig` |
| `notification.live.events` (exchange) | `notification.#` (wildcard, exchange-to-exchange) | `RabbitMqTopologyConfig` |
| `post.live.events` (exchange) | `post.live.#` (wildcard, exchange-to-exchange) | `RabbitMqTopologyConfig` |

**RabbitMQ configuration (application.yaml):**
- `publisher-confirm-type: correlated` — broker confirms wired to outbox acknowledge logic
- `publisher-returns: true` — unroutable messages returned to sender
- `template.mandatory: true` — mandatory flag on every send
- `listener.simple/direct.acknowledge-mode: manual` — consumers ack/nack explicitly

### Analytics - ClickHouse

- Database `luvax_analytics` on the observability stack's ClickHouse, pinned to the 26.3 LTS line (`26.3.33.24`).
- The database, its users and its settings profiles are provisioned by `observability/clickhouse/initdb/02-create-analytics.sh`, not by the application.
- Local development reaches it at `jdbc:clickhouse://localhost:8123/luvax_analytics`; production reaches it over the `coolify` network.
- Client: `com.clickhouse:jdbc-v2` 0.10.0 through plain JDBC and `JdbcClient`; every call goes through `ClickHouseOperations` (`read`, `readBatch`, `write`, `isReady`).
- `app.analytics.enabled` (`ANALYTICS_ENABLED`, default `true`) switches the tier off.
  Surefire pins it to `false`; with it off, `DisabledClickHouseOperations` throws `ClickHouseUnavailableException` on every call, so reads that have a fallback use it and the analytics consumers are absent.

**Local development**:
- Start ClickHouse with `docker compose -f observability/compose.local.yaml --profile observability up -d clickhouse`; on a fresh volume `initdb` provisions the users, and on an existing volume run `docker exec <clickhouse container> bash /docker-entrypoint-initdb.d/02-create-analytics.sh`.
- Set `ANALYTICS_CLICKHOUSE_WRITER_PASSWORD`, `ANALYTICS_CLICKHOUSE_READER_PASSWORD` and `ANALYTICS_CLICKHOUSE_MIGRATOR_PASSWORD` in `.env` to the values of the matching `CLICKHOUSE_ANALYTICS_*_PASSWORD` variables of the observability stack; the other `ANALYTICS_*` keys in `.env.example` have working local defaults.
- The backend starts without ClickHouse, retries the schema every 30 seconds, and serves the audit log from PostgreSQL until it is ready.

**Tables** (all `ReplacingMergeTree`, all with `fsync_after_insert = 1`):

| Table | Role | Sorting key | Retention |
|-------|------|-------------|-----------|
| `user_events` | System of record | `(user_id, event_type, created_at, id)`, monthly partitions | 12 months; whole monthly parts are dropped (`ttl_only_drop_parts`) |
| `admin_actions` | Replica of PostgreSQL `admin_actions`, version column `row_version` | `(created_at, id)`, yearly partitions | Permanent |
| `platform_stats` | System of record, version column `computed_at` | `(metric_key, bucket_start, dimension)`, yearly partitions | Permanent |

**Users and profiles** (created by the provisioning script):

| User | Grants | Used by |
|------|--------|---------|
| `luvax_analytics_writer` | `INSERT` on `luvax_analytics.*` | consumers and `UserEventRecorder` |
| `luvax_analytics_reader` | `SELECT` on `luvax_analytics.*` | request-path reads and the batch reads |
| `luvax_analytics_migrator` | DDL, `TRUNCATE`, `OPTIMIZE`, `SELECT`, `INSERT` on `luvax_analytics.*` only | the migration runner and the seed reset |
| `grafana_reader` | `SELECT` on `luvax_analytics.*` and on `system.asynchronous_insert_log` | the Business dashboards |

Each application user runs under a settings profile with a per-user memory cap marked `CONST` (writer 512 MiB, reader 1 GiB, migrator 512 MiB), `max_threads = 2` and a query time cap, so analytics can never hold more than 2 GiB of the server's memory.

**Pools** (`ClickHouseDataSourceConfig`, `@Bean(defaultCandidate = false)` with a `@Qualifier`, so an unqualified `DataSource` or `JdbcClient` is still PostgreSQL):
- `writer` (12 connections, socket timeout `PT10S`), `reader` (6, `PT25S`), `batch` (2, `PT5M`, reader credentials) and `migrator`.
- Every pool sets `retry=0`, a 2 second connection and checkout timeout, and `initializationFailTimeout=-1`, so the application starts while ClickHouse is down.
- `NetworkTimeoutDataSource` sets the socket timeout on every checkout, because HikariCP resets it to 0 after validating a connection (the driver reports 0) and a hung server would otherwise block callers forever.
- `PrimaryDatabaseHealthConfig` declares a `dbHealthIndicator` over the primary `DataSource` only, so ClickHouse never reaches `/actuator/health`.

**Failure model** (`ClickHouseErrorTranslator`):
- A driver `SQLException` is translated by ClickHouse error code and SQLState, never by Spring's exception type, because every server error carries SQLState `22000`.
- A rejected request (a parse, type, unknown-column, result-size, setting-constraint or unknown-enum error) becomes `ClickHouseRequestRejectedException`.
- Everything else, including an unknown code, a refused connection, a timeout, a memory limit, a missing table, a privilege or an authentication failure, becomes `ClickHouseUnavailableException`, which pauses ingestion rather than dead-lettering data.
- `ClickHouseOperationsImpl` runs every call through the `clickhouse` circuit breaker, which records only `ClickHouseUnavailableException`.
- Before the schema gate is ready every call fails with `ClickHouseUnavailableException(NOT_READY)` without touching the breaker.

**Schema management**: an application-owned runner, `ClickHouseMigrationRunner`, applies `src/main/resources/clickhouse/migration/V{n}__{description}.sql` as `luvax_analytics_migrator`.
- Versions apply in ascending order, and a version lower than the highest applied is refused.
- The checksum is SHA-256 of the script with line endings normalised to LF, and an edited applied script is refused.
- Every statement must be safe to run twice, and the history row in `luvax_analytics.schema_migrations` is written only after the last statement succeeds, so no repair step exists.
- A PostgreSQL advisory lock keeps two instances from migrating at once.
- `AnalyticsSchemaGate` runs the runner at startup and every `app.analytics.migration.retry-interval` (`PT30S`) until it succeeds, then publishes `AnalyticsSchemaReadyEvent`.
- Adding a `UserEventType` value means adding the value to the enum and, in the same commit, a `V{n}` script that runs `ALTER TABLE user_events MODIFY COLUMN event_type Enum8(...)` with the full value list.

**Writes** (all through the outbox, except the recorder):

| Event | Producer | Consumer (listener id, concurrency) |
|-------|----------|-------------------------------------|
| `admin.action.recorded.v1`, `admin.action.changed.v1` | `AdminActionRecorder`, the V127 trigger, the seed emitter | `AdminActionReplicationConsumer` (`adminActionReplication`, 1) |
| `admin.platform-stats.collected.v1` | `PlatformStatsCollectionServiceImpl`, the seed | `PlatformStatsIngestConsumer` (`platformStatsIngest`, 2) |
| `recommendation.user-event.imported.v1` | the seed | `UserEventImportConsumer` (`userEventImport`, 2) |
| `post.liked.v1`, `post.saved.v1`, `post.viewed.v1`, `comment.created.v1`, `post.shared.v1`, `comment.liked.v1` | the owning modules | `RecommendationFeedbackConsumer` (`recommendationFeedback`, `app.recommendation.consumer.concurrency`, default 4) |

- Consumers insert with `async_insert = 1, wait_for_async_insert = 1`, so the acknowledgement follows the part write; the busy timeout (5 to 20 ms) comes from the writer profile.
- `UserEventRecorder` inserts with `wait_for_async_insert = 0`, at most 8 writes in flight, and drops a row rather than fail the request; drops are counted by reason and ClickHouse's own `FailedAsyncInsertQuery` counter covers the silent failures.
- Replication is notify-then-fetch: the consumer reads the current PostgreSQL row by id and writes it with its `row_version`, so a late, duplicate or out-of-order event can never regress the replica.
- On `ClickHouseUnavailableException` a consumer nacks with requeue; any other failure dead-letters as before.
- `AnalyticsIngestionController` stops the four analytics listener containers while the `clickhouse` breaker is open and starts them when it half-opens or closes, so messages wait in their queues instead of reaching a dead-letter queue.
  It also exposes `suspend(reason)` and `resume(reason)`, which the Gorse rebuild uses to hold the feedback listener.

**Reads**:

| Read | Store | Behaviour when ClickHouse is unavailable |
|------|-------|------------------------------------------|
| Audit-log listing (`GET /admin/actions`, per-user) | ClickHouse, `FINAL`, ties ordered by `toString(id)` | Falls back to PostgreSQL with the same cursor; `luvax.analytics.audit_log.fallback` counts it |
| Audit action by id, appeal recovery, support appeals, moderation notices | PostgreSQL | Not affected |
| Activity log (`AdminUserEventServiceImpl`) | ClickHouse, `FINAL`, mandatory window | `503 ANALYTICS_UNAVAILABLE` |
| Platform statistics current and series | ClickHouse, `FINAL` | `503 ANALYTICS_UNAVAILABLE` for the whole response |
| For You read-set (`RecommendationSource`) | ClickHouse, no `FINAL` (a set, duplicates are harmless) | Degrades to Gorse results only; never touches the `gorse` breaker |
| Hashtag affinity recompute | ClickHouse signals into a temporary PostgreSQL stage table | The previous rows stay; the job waits for the next cycle |

**Metrics** (Prometheus names): `luvax_analytics_user_events_dropped_total{reason}`, `luvax_analytics_audit_log_fallback_total`, `luvax_analytics_ingestion_running{listener}`, `luvax_analytics_schema_ready`, and the `luvax_gorse_rebuild_*` family.

**Rollback**: `scripts/rollback/phase2_postgres_rollback.sql` recreates the dropped tables and removes V127 to V133 from `flyway_schema_history`.
It must run with the new backend stopped and before the previous backend image starts, because that image refuses to start on Flyway rows it does not know.

### Search — Elasticsearch

- docker-compose: `docker.elastic.co/elasticsearch/elasticsearch:9.2.5` (port 9200, `discovery.type: single-node`, security disabled). Pinned to production's 9.2.5, up from 9.0.3.
- Client wired in `ElasticsearchConfig` using `app.elasticsearch.*` properties (URIs, optional Basic Auth, connection/socket timeouts)
- Index settings files: `src/main/resources/elasticsearch/settings/posts.json`, `hashtags.json`

**Indexed documents:**

| Document class | Index | `createIndex` | Setting path |
|----------------|-------|---------------|--------------|
| `PostDocument` | `posts` | `false` | `/elasticsearch/settings/posts.json` |
| `HashtagDocument` | `hashtags` | `false` | `/elasticsearch/settings/hashtags.json` |

`PostDocument` fields: `id`, `user_id` (Keyword), `caption` (Text + `caption.ngram`), `status` (Keyword), `hashtag_ids` (Keyword list), `created_at` (Date).

`HashtagDocument` fields: `id`, `name` (Keyword + `name.ngram`), `post_count` (Integer), `created_at` (Date).

Indexes are created at startup by `PostIndexSeedRunner` / `HashtagIndexSeedRunner` when Elasticsearch is reachable. Documents are synchronized asynchronously via outbox events consumed by `PostIndexSyncConsumer` / `HashtagIndexSyncConsumer`. The `elasticsearchSearch` circuit breaker gates search queries; fallback for hashtag search is PostgreSQL trigram.

### Resilience

Pre-configured Resilience4j (dev and prod profiles):

| Component | Instance | Dev config | Prod config |
|-----------|----------|------------|-------------|
| Circuit breaker | `default` | 10-call sliding window, 5 min calls, 50% failure threshold, 10s open wait, 3 half-open calls | 20-call sliding window, 10 min calls, 50% threshold, 30s open wait, 5 half-open calls |
| Circuit breaker | `clickhouse` | COUNT_BASED 10-call, 5 min calls, 50% threshold, 10s open wait, 3 half-open, auto-transition; records only `ClickHouseUnavailableException`, ignores `ClickHouseRequestRejectedException`; no health indicator | COUNT_BASED 20-call, 10 min calls, 50% threshold, 30s open wait, 3 half-open, auto-transition, 10s slow-call threshold, 80% slow-call rate; same exception lists; no health indicator |
| Circuit breaker | `elasticsearchSearch` | COUNT_BASED 10-call, 50% threshold, 10s open wait, 3 half-open, auto-transition | COUNT_BASED 20-call, 50% threshold, 30s open wait, 5 half-open, auto-transition, 5s slow-call threshold, 80% slow-call rate |
| Rate limiter | `gorseRebuild` | `app.recommendation.gorse-rebuild.requests-per-second` (default 5) per 1s, 30s timeout | same |
| Rate limiter | `lowTraffic` | 3,000 req / 30s, 5s timeout | 3,000 req / 30s, 5s timeout |
| Rate limiter | `mediumTraffic` | 6,000 req / 30s, 5s timeout | 6,000 req / 30s, 5s timeout |
| Rate limiter | `highTraffic` | 9,000 req / 30s, 5s timeout | 9,000 req / 30s, 5s timeout |
| Retry | `default` | 3 attempts, 1s initial, ×2 backoff | 3 attempts, 2s initial, ×2 backoff |

The three rate limiter instances are JVM-wide overload backstops, not per-client budgets, and are
deliberately sized well above any per-client budget so one caller cannot starve the shared bucket.
Per-caller limiting is the Redis sliding window in `AuthRateLimitFilter`, configured under
`app.rate-limit.endpoint-rules`, which is where an endpoint's real budget is set.
The figures above were recorded as 60, 120 and 120 while the files said 3,000, 6,000 and 9,000,
which made the administrative surface read as throttled when no per-caller rule covered it at all.

`app.rate-limit.endpoint-rules` accepts Ant patterns as well as exact paths, so a path-variable
route can carry a rule.
`AuthRateLimitFilter` tries an exact match first, then the most specific matching pattern, and
buckets on the key that matched rather than on the concrete request path.

---

## 4. Technology Stack

| Component | Value |
|-----------|-------|
| Language | Java 21 (virtual threads: `spring.threads.virtual.enabled: true`) |
| Framework | Spring Boot 4.0.6 |
| Database | PostgreSQL |
| Cache | Redis |
| Message broker | RabbitMQ |
| Search | Elasticsearch 9.2.5 (`spring-boot-starter-data-elasticsearch`) |
| Analytics store | ClickHouse 26.3 LTS (`com.clickhouse:jdbc-v2` 0.10.0) |
| Object storage | Cloudflare R2 via AWS SDK v2 (`software.amazon.awssdk:s3 2.25.60`) |
| Transactional email | Resend SDK (`resend-java 3.1.0`) |
| Build | Maven (`./mvnw`) |
| Migrations | Flyway (`spring-boot-starter-flyway`, `flyway-database-postgresql`) |
| Resilience | Resilience4j (Spring Cloud 2025.1.1) |
| Security | Spring Security 6 |
| ORM | Spring Data JPA / Hibernate |
| Code generation | Lombok, MapStruct 1.6.3 |
| Observability | Micrometer Tracing with the OpenTelemetry bridge, OTLP export of traces and logs, Prometheus scrape on the management port, datasource-micrometer 2.2.1, Spring Actuator |
| Formatting | Spotless 2.46.1 (Google AOSP); run `./mvnw spotless:apply` |
| Testing | JUnit 5, Testcontainers 1.21.4 (postgresql, clickhouse, elasticsearch), Spring Boot test starters |
| CI/CD | GitHub Actions (`.github/workflows/pr-lint.yml`, `pr-size.yml`, `sonarcloud.yml`) |

---

## 5. Security

Implemented in `common/security/` and `modules/auth/`:

- **JWT**: `JwtTokenProvider` issues HS256-signed access tokens. Claims include `jti` (UUID per token, used for blacklisting). TTLs from `ACCESS_TOKEN_TTL` / `REFRESH_TOKEN_TTL`. Signing key from `JWT_SECRET`; issuer from `JWT_ISSUER`; audience from `JWT_AUDIENCE`.
- **Filter chain**: `JwtAuthenticationFilter` → `AuthRateLimitFilter`. `CachedBodyHttpServletRequest` enables body re-read in filters with a configurable byte-size limit.
- **Account state enforcement**: `UserStateValidator` throws typed `AppException` for banned, suspended, deactivated, and unverified-email states. `JwtAuthenticationFilter` also enforces status on every authenticated request.
- **Token blacklist**: Redis-backed (`TokenBlacklistServiceImpl`); on logout, `jti` stored until access token expiry (`auth:blacklist:{jti}`).
- **Refresh tokens**: SHA-256 hashed (`token_hash`) in PostgreSQL with device metadata and IP; revocable via `revoked_at`. `rotate()` uses `REQUIRES_NEW` propagation and a conditional UPDATE for concurrent-rotation detection and token-theft detection.
- **One-time tokens**: Email verification and password reset stored as SHA-256 hashes in Redis with TTL; atomic Lua-script consumption; issuing a new token invalidates the prior via reverse index.
- **OAuth2**: `CustomOidcUserService`, `OAuth2AuthenticationSuccessHandler`, `OAuth2AuthenticationFailureHandler` in `auth/oauth2/`. State parameter persisted as a tamper-evident HMAC-signed cookie via `CookieOAuth2AuthorizationRequestRepository` (key from `APP_COOKIE_SIGNING_SECRET`).
- **OAuth2 code exchange**: `OAuth2ExchangeCodeServiceImpl` stores a short-lived user-ID mapping (`auth:oauth2:exchange:{code}`, 120s TTL) consumed once by the frontend to complete the PKCE-like handshake.
- **Rate limiting**: `AuthRateLimitFilter` uses `RateLimiterServiceImpl` (Redis Lua sliding window); per-endpoint rules in `app.rate-limit.endpoint-rules`. Login bucket keyed by IP + email.
- **Trusted proxy**: `IpExtractor` reads `X-Forwarded-For` only when the direct peer IP matches a configured trusted-proxy CIDR list (`app.security.trusted-proxy-cidrs`).
- **Forgot-password timing**: `ForgotPasswordTimingEqualizer` normalises response time to a configurable floor (`app.auth.forgot-password.min-response-time` + jitter) to prevent user-enumeration via timing.
- **Management port**: `ManagementSecurityConfig` installs a second, highest-precedence `SecurityFilterChain` matched by `ManagementServerRequestMatcher` (any request served by the management server's own port, distinct from the application port). Only `/actuator/health`, `/actuator/health/**` and `/actuator/prometheus` are permitted there; everything else on that port is denied. The application's own filter chain is unaffected, because Spring Boot would otherwise apply it to the management context too. The `app.security.public-metrics-endpoint` property this superseded has been removed.

---

## 6. Key Conventions

- **Naming**: `{Entity}Controller`, `{Entity}Service` / `{Entity}ServiceImpl`, `{Entity}Repository`, `{Entity}Request` / `{Entity}Response`
- **Formatting**: Spotless / Google Java Format AOSP; import order: `java`, `jakarta`, `org`, `com`; 4-space tab indent
- **Transactions**: `@Transactional` on Service impl methods only — never on interfaces or Controllers
- **Responses**: wrap all responses in `ApiResponse<T>`; use `PageResponse<T>` for offset pagination, `CursorPageResponse<T>` for cursor pagination
- **Async**: virtual threads enabled globally
- **Logging**: `timestamp | level | thread | traceId | logger | message`; rolling file 50 MB per file, 3 days, 200 MB cap. An `OpenTelemetryAppender` carries every record to the OTLP log exporter, which is now the durable copy (14 day retention in ClickHouse); the local file only needs to bridge an export outage or serve a machine with no collector configured.
- **Comments**: English only; see `.claude/rules/comment_style.md` (mirrored at `.agents/rules/comment_style.md`)

---

## 7. Domain-Specific Invariants

- **Denormalized counters** (maintained by PostgreSQL triggers — never write from application code):
  - `users`: `follower_count`, `following_count`, `post_count`
  - `posts`: `like_count`, `comment_count`, `save_count`, `view_count`
  - `comments`: `like_count`, `reply_count`
  - `hashtags`: `post_count`
  - `stories`: `view_count`, `like_count`
  - `notifications`: `actor_count`
- **`user_events`**: lives in ClickHouse, not PostgreSQL.
  The sorting key is `(user_id, event_type, created_at, id)` and the engine keeps one row per key, so a redelivered event folds away on merge; reads that must be exact use `FINAL`, and a read that only builds a set (the For You read-set) does not.
  `event_type` is an `Enum8`, so an unknown value is rejected (code 691) the way the PostgreSQL enum rejected it, and adding a `UserEventType` value needs a ClickHouse migration in the same commit.
  Rows are kept 12 months; whole monthly parts are dropped once every row has expired.
  Every read is bounded by `created_at`: the activity log requires a window of at most 30 days.
  `feedback_type` and `feedback_value` hold the exact Gorse feedback the row was sent with, null when it was never sent, so a Gorse rebuild reproduces what the live pipeline accumulated.
  `created_at` is always supplied by the writer, never defaulted, because an async insert is processed at flush time.
- **`platform_stats`**: lives in ClickHouse and is written only through `admin.platform-stats.collected.v1`, never from a Controller or Service on a request path.
  Gauges are absolute snapshots bounded by the bucket end; flows are direct counts over the bucket window and are never derived by subtracting consecutive gauges.
  Only half-hour buckets are stored; daily figures are computed at query time, summing flows and taking the last bucket of the day for gauges, because summing gauges multiplies a total by the number of buckets in the day.
  A day belongs to a daily series when its UTC midnight falls in the window, and the day in progress is never returned.
  There is no backfill: a bucket never collected can never be collected later, and the bucket a process starts inside is deliberately skipped.
  A single application instance is assumed; a double run restates a bucket rather than doubling it, because both events land on the same sorting key and the later `computed_at` wins under `FINAL`.
- **`admin_actions` replica**: PostgreSQL is the system of record and ClickHouse holds a listing replica.
  The application never updates a row, but PostgreSQL does: `admin_id`, `target_user_id` and `report_id` are `ON DELETE SET NULL`.
  A `row_version` column, bumped by a trigger on every real change, lets `ReplacingMergeTree(row_version)` keep the newest state whatever order events arrive in, and a second trigger enqueues `admin.action.changed.v1` in the same transaction.
  Reads that dereference an audit row by id (the detail drawer, appeal recovery, support appeals, moderation notices, the discipline ladder, `notifications.admin_action_id`) stay on PostgreSQL; only the listing reads the replica, and it falls back to PostgreSQL under the same cursor, with ties ordered by `toString(id)` because ClickHouse's native UUID order differs from PostgreSQL's.
- **Analytics reads lag writes by seconds**: an analytics row is visible after the outbox publishes it and the consumer writes it, so a screen fed by ClickHouse can trail the action that produced it.
- **Message tombstones**: `messages` carries two independent ones. The sender-owned pair `is_deleted`/`deleted_at` is set together and clears `content`, which makes a sender's own deletion irreversible. `admin_removed_at` (V77) is the moderation tombstone; it preserves every payload so a restore can return the message, and clearing it never undoes a sender deletion. A message is hidden when either is set, and the read path withholds text, media and shares for an administrative removal.
- **Comment and story tombstones**: both tables carry two independent ones since V95. `deleted_at` is the owner's own deletion; `admin_removed_at` is the moderation tombstone. A row is hidden when either is set, an administrative restore clears only `admin_removed_at` and never undoes an owner deletion, and the entity `@SQLRestriction` on both covers every JPQL and derived read, so only native SQL restates the predicate. `posts` solved the same problem with `status_before_moderation` (V59) and `messages` with `admin_removed_at` (V77). Rows an administrator removed before V95 keep only their `deleted_at`, so they stay hidden but now read as owner deletions and can no longer be administratively restored; there was no way to tell them apart and no backfill was attempted.
- **Story moderation and expiry**: expiry keeps deciding visibility independently of both tombstones, so a story that expired while removed does not return to a feed when restored, and the cleanup job hard-deletes rows that are expired and carry either tombstone, after which a restore answers not-found.
- **`user_settings`**: every account has exactly one row from creation. Four writers create it - password registration, first OAuth2 sign-in, `UserSeedWriter` (the dev-database seed pipeline's user writer) and the shell seed script - and V78 backfilled the rest. The read is deliberately strict, so a 404 from it means the invariant is broken rather than that the account simply has no preferences yet.
- **Removed group-conversation columns**: V50 moved `is_group`, `group_name`, and `group_avatar_url` off `conversations` into a new `archived_group_conversations` table, and moved the group's participant and message rows into `archived_group_participants` and `archived_group_messages`. `conversations` today carries `id`, `created_by`, `last_message_at`, `created_at`, `updated_at`, `direct_pair_key`. See `docs/modules/message/DATA_RULES.md`.
- **Comment depth cap**: `CHECK (depth BETWEEN 0 AND 10)`; adjacency list uses `root_id` for subtree queries.
- **Follow visibility**: insert to `follows` with `is_private = true` target → `status = 'pending'`; counters increment only on `status = 'accepted'` (trigger-enforced).
- **Story expiry**: `expires_at DEFAULT NOW() + INTERVAL '24 hours'`; `idx_stories_expires` implies a cleanup job.
- **Config tables**: `notification_type_configs`, `moderation_action_configs`, `report_reason_configs` (V18) store display metadata for enum values. See `docs/modules/GLOBAL_RULES.md` for the enum/config table contract.
- **Swagger path**: configured at `/api-docs` in `application-dev.yml`; disabled in prod.
