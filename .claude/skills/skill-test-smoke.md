---
trigger: model_decision
description: Load when reviewing or modifying the smoke test. Covers the purpose, annotation stack, required container and env-var setup, and what to assert in ApplicationTests.java.
---

# Skill: Smoke Test

## When to use

Verifying the full ApplicationContext loads after any significant structural change (new module, new config bean, Flyway migration, new dependency). There is exactly one smoke test file: `src/test/java/com/app/ApplicationTests.java`. Never split it.

## Purpose

Catch wiring failures — missing beans, broken `@ConfigurationProperties`, Flyway migration errors — before integration tests run. This is the cheapest safety net: context load failure is caught immediately.

## Annotation stack

```java
@SpringBootTest(
    properties = {
        "spring.profiles.active=dev",
        "spring.docker.compose.enabled=false",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
    })
@Testcontainers
class ApplicationTests { }
```

No `webEnvironment` — the default (`MOCK`) is sufficient. Flyway runs on context startup, so PostgreSQL is required.

## Container and env-var setup

```java
@Container @ServiceConnection
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

@Container
static GenericContainer<?> redis =
    new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

@DynamicPropertySource
static void register(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    r.add("JWT_SECRET", () -> "smoke-test-secret-32-chars-minimum-len!!!!");
    r.add("JWT_ISSUER", () -> "https://smoke.test.local");
    r.add("ACCESS_TOKEN_TTL", () -> 900L);
    r.add("REFRESH_TOKEN_TTL", () -> 3600L);
    r.add("APP_BASE_URL", () -> "http://localhost:8080");
    r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
    r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
    r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
    r.add("MAIL_FROM_NAME", () -> "App Smoke");
    r.add("MAIL_APP_NAME", () -> "App");
    r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
    r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
    r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
    r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
}
```

## External side-effect stub

Stub `MailService` to prevent real email sends during context startup or any `@EventListener` post-construct wiring:

```java
@MockitoBean private MailService mailService;
```

## Test body

```java
@Test
void contextLoads() {}
```

An empty body is correct. If the context fails to load, Spring throws before the method executes. Optionally add `@Autowired` assertions for key beans (e.g., `JwtTokenProvider`, `SecurityConfig`) when you want explicit confirmation of critical wiring.

## When to run

Always — `./mvnw test` includes it. Run explicitly after:

- Adding a new `@Configuration` or `@ConfigurationProperties` class
- Adding a new Flyway migration
- Changing the Spring profile structure
- Adding a new module with Spring beans

## Checklist

- [ ] File is `src/test/java/com/app/ApplicationTests.java` — one file only
- [ ] `@SpringBootTest` with no `webEnvironment` (default MOCK)
- [ ] `@Testcontainers` present
- [ ] PostgreSQL `postgres:18-alpine` with `@ServiceConnection`
- [ ] Redis `redis:7-alpine` with `@DynamicPropertySource`
- [ ] All required env vars supplied in `@DynamicPropertySource`
- [ ] `@MockitoBean MailService` present
- [ ] Single `void contextLoads() {}` test method
