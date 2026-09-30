---
trigger: model_decision
description: Load when writing integration tests for any controller. Covers annotation stack, container setup, request building, auth pattern, and database assertion derived from AuthControllerIT.
---

# Skill: Integration Test (Controller)

## When to use

Writing end-to-end tests for any class in `modules/{module}/controller/`.

## Input required

- Controller under test
- Which services have external side effects (stub them — always include `MailService`)
- Full list of required env vars for `@DynamicPropertySource`

## Steps

1. Name the class `{Module}ControllerIT` in `src/test/java/com/app/modules/{module}/controller/`.
2. Class-level annotation stack:
   ```java
   @SpringBootTest(
       webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
       properties = {
           "spring.profiles.active=dev",
           "spring.docker.compose.enabled=false",
           "spring.autoconfigure.exclude=" +
               "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
       })
   @Testcontainers
   @AutoConfigureTestRestTemplate
   @Import({ModuleControllerIT.IntegrationTestConfig.class})   // only when test-only beans needed
   class {Module}ControllerIT { }
   ```
3. Declare containers as `static` fields:
   ```java
   @Container @ServiceConnection
   static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

   @Container
   static GenericContainer<?> redis =
       new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
   ```
4. Supply all required env vars via `@DynamicPropertySource`:
   ```java
   @DynamicPropertySource
   static void register(DynamicPropertyRegistry r) {
       r.add("spring.data.redis.host", redis::getHost);
       r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
       r.add("JWT_SECRET", () -> "integration-test-secret-32-chars-minimum-len!!");
       r.add("JWT_ISSUER", () -> "https://it.test.local");
       r.add("ACCESS_TOKEN_TTL", () -> 900L);
       r.add("REFRESH_TOKEN_TTL", () -> 3600L);
       r.add("APP_BASE_URL", () -> "http://localhost:8080");
       r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
       r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
       r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
       r.add("MAIL_FROM_NAME", () -> "App IT");
       r.add("MAIL_APP_NAME", () -> "App");
       r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
       r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
       r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
       r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
   }
   ```
5. Inject via `@Autowired`: `TestRestTemplate rest`, repositories needed for DB assertions.
6. Stub external-I/O services with `@MockitoBean` (Spring Boot 4.x):
   ```java
   @MockitoBean private MailService mailService;
   ```
7. Write one `@Test` per scenario. Method name: `{method}_{condition}_{outcome}`.
8. HTTP calls via `TestRestTemplate`:
   ```java
   ResponseEntity<Map> response = postJson("/api/v1/auth/register", body);
   assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
   assertThat(response.getBody().get("code")).isEqualTo("CREATED");
   ```
9. Capture verification tokens from mocked `MailService` with `ArgumentCaptor`:
   ```java
   ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
   verify(mailService, atLeastOnce()).sendEmailVerification(eq(email), anyString(), urlCaptor.capture());
   String token = urlCaptor.getAllValues().get(urlCaptor.getAllValues().size() - 1);
   ```
10. Assert database state via repository injection:
    ```java
    RefreshToken row = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    assertThat(row.getRevokedAt()).isNotNull();
    ```
11. Mint test JWTs directly using `NimbusJwtEncoder` when testing JWT expiry or tampering scenarios.
12. Use `uniqueEmail(tag)` helpers (UUID suffix) to avoid cross-test data conflicts — do not wipe the DB between tests.
13. Register test-only `@RestController` beans via `@TestConfiguration` + `@Import` when the test needs a protected endpoint:
    ```java
    @TestConfiguration
    static class IntegrationTestConfig {
        @Bean TestProtectedEndpoint testProtectedEndpoint() { return new TestProtectedEndpoint(); }
    }
    ```
14. No `@Transactional` on the test class — committed transactions must be visible to repository assertions.

## Output contract

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` with dev profile and Rabbit excluded
- `@Testcontainers @AutoConfigureTestRestTemplate`
- `postgres:18-alpine` with `@ServiceConnection`; `redis:7-alpine` with `@DynamicPropertySource`
- All required env vars in `@DynamicPropertySource`
- `MailService` stubbed with `@MockitoBean`
- DB state verified via repository after key mutations
- No `@Transactional` on the test class

## Checklist

- [ ] `@SpringBootTest(webEnvironment = RANDOM_PORT)` with correct `properties`
- [ ] `@Testcontainers @AutoConfigureTestRestTemplate` present
- [ ] PostgreSQL `postgres:18-alpine` with `@ServiceConnection`
- [ ] Redis `redis:7-alpine` with `@DynamicPropertySource` for host/port
- [ ] All required env vars in `@DynamicPropertySource`
- [ ] `MailService` stubbed with `@MockitoBean`
- [ ] No `@Transactional` on the test class
- [ ] DB state verified via repository injection after key assertions
