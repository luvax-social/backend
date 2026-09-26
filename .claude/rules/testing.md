---
trigger: model_decision
description: Load when writing, running, or reviewing any test class. Contains test type definitions, naming conventions, annotation rules, and what to run before committing.
---

# Testing Rules

## 1. Test Type Definitions

| Type | Annotation | Scope | Speed | When to write |
|---|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Single class, all deps mocked | Fast | Every Service impl method with logic |
| Unit (pure) | None required | Single class, no mocks needed | Fast | Value objects, providers with no deps |
| Integration | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers | Full context, real PostgreSQL + Redis | Slow | Critical user flows end-to-end |
| Smoke | `@SpringBootTest` (no `webEnvironment`) | ApplicationContext loads | Medium | `ApplicationTests.java` — one per app |

`@WebMvcTest` slices are not used.
`@DataJpaTest` slices, backed by Testcontainers PostgreSQL via `@Container @ServiceConnection`, are the established pattern for repository integration tests that exercise only repository queries and the SQL they emit.
Every `*RepositoryIT` uses this slice: `FollowRepositoryIT`, `HashtagRepositoryIT`, `MediaAssetRepositoryIT`, `OutboxEventRepositoryIT`, `ReportRepositoryIT`, `UserHashtagAffinityRepositoryIT` and `UserWarningRepositoryIT`.
The slice is not confined to that naming: 28 test classes carry `@DataJpaTest` in total, including the `*KeysetRowLossIT` family and other query-level tests.
List them with `git grep -l '@DataJpaTest' -- src/test` rather than trusting this sentence to have kept up.
The slice is sound for these because a native `@Query` emits identical SQL whether its repository is loaded in the slice or the full context, and native queries bypass `@SQLRestriction` in both, so the repository behaviour under test is the same either way.
Use a full `@SpringBootTest` for any test that spans more than the repository layer.

## 2. Naming Conventions

| Class suffix | When to use | Example |
|---|---|---|
| `Test` | Unit test or pure unit test | `AuthServiceImplTest`, `JwtTokenProviderTest` |
| `IT` | Integration test (`@SpringBootTest` + real containers) | `AuthControllerIT` |
| `Tests` | Smoke test only | `ApplicationTests` |

Test method names: `{method}_{condition}_{outcome}` — e.g., `register_duplicateEmail_throwsConflict`.

Maven Surefire includes `**/*Test.java`, `**/*Tests.java`, `**/*IT.java`. Never use other suffixes.

## 3. Annotation Rules

**Unit tests with `@Mock` fields:** require `@ExtendWith(MockitoExtension.class)`.  
**Pure unit tests** (direct construction, no `@Mock`): no extension needed.

**Mock annotation:** use `@MockitoBean` (Spring Boot 4.x, `org.springframework.test.context.bean.override.mockito`). Never use legacy `@MockBean`.

**Testcontainers images (enforced):**
- PostgreSQL: `postgres:16-alpine` with `@Container @ServiceConnection`
- Redis: `redis:7-alpine` with `@Container` + `@DynamicPropertySource`

**No shared base class.** Each test class declares its own containers and `@DynamicPropertySource`. Container fields must be `static`.

**`@Transactional` must not appear on `@SpringBootTest` classes.** Committed transactions must be visible to assertions that query the DB directly.

**Unit test construction:** instantiate the impl via constructor in `@BeforeEach`. Do not use `@InjectMocks`.

**`lenient()` stubs:** use only in `@BeforeEach` for stubs not exercised by every test method.

## 4. Context Cache Rule

Do not use `@MockitoBean` in `@SpringBootTest` for beans that are not external side-effect services (e.g., email, SMS). Each `@MockitoBean` declaration busts the ApplicationContext cache and creates a new context per test class. Stub only `MailService` and equivalent external-I/O services.

## 5. Required `@DynamicPropertySource` Properties

Every `@SpringBootTest` test class must supply at minimum:

```
spring.data.redis.host / port
JWT_SECRET (≥ 32 chars)
JWT_ISSUER
ACCESS_TOKEN_TTL / REFRESH_TOKEN_TTL
APP_BASE_URL / CORS_ALLOWED_ORIGINS
RESEND_API_KEY / MAIL_FROM_ADDRESS / MAIL_FROM_NAME / MAIL_APP_NAME
FRONTEND_BASE_URL
GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
spring.datasource.hikari.data-source-properties.stringtype = unspecified
```

Always include:

```java
"spring.profiles.active=dev",
"spring.docker.compose.enabled=false",
"spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
```

(Omit the `spring.autoconfigure.exclude` line in `@SpringBootTest` classes that wire and exercise a real RabbitMQ broker via Testcontainers — adding it would disable the consumer under test.)

## 6. Before Committing

```bash
./mvnw test
```

Docker must be running. All types run under the default `./mvnw test` phase.

## 7. What NOT To Do

- No H2 in-memory database — always Testcontainers PostgreSQL.
- No `Thread.sleep()` — Awaitility is not in scope; redesign async tests to avoid polling.
- No `System.out.println()` in test code.
- No `@Disabled` without a tracked issue reference.
- No test class that imports from another test class.
- No `@Transactional` on `@SpringBootTest` classes.
- No `@InjectMocks` — construct the impl manually.
