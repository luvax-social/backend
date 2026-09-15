package com.app.common.config.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.admin.enums.PlatformMetric;
import com.app.modules.mail.service.MailService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Structural contract test over the served OpenAPI document.
 *
 * <p>Asserts invariants, never counts, and derives every subject set from the document itself
 * rather than from a hardcoded list of paths. A new endpoint therefore does not require editing
 * this test: a correct one keeps it green, an under-declared one turns it red. That property is the
 * point, because the defect class this guards against was introduced one hand-written annotation at
 * a time.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class OpenApiContractIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "openapi-contract-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://openapi-contract.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
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

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate restTemplate;

    private static JsonNode document;

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @BeforeAll
    static void resetDocument() {
        document = null;
    }

    private JsonNode document() {
        if (document == null) {
            String body = restTemplate.getForObject("/api-docs", String.class);
            assertThat(body).as("served OpenAPI document").isNotBlank();
            document = new ObjectMapper().readTree(body);
        }
        return document;
    }

    @Test
    void everySuccessResponseDeclaresTheApiResponseEnvelope() {
        JsonNode doc = document();
        List<String> offenders = new ArrayList<>();

        forEachSuccessResponseWithContent(
                doc,
                (operationId, code, mediaType, schema) -> {
                    String ref = refName(schema);
                    if ("ApiResponse".equals(ref)) {
                        // The bare envelope leaves `data` untyped, so a generated client gets
                        // Object for the payload.
                        offenders.add(operationId + " " + code + " -> bare ApiResponse");
                        return;
                    }
                    JsonNode resolved = resolve(doc, schema);
                    if (resolved == null || !resolved.path("properties").has("data")) {
                        // A payload type declared as the whole body is factually wrong: the
                        // server always wraps it in ApiResponse<T>.
                        offenders.add(
                                operationId + " " + code + " -> " + ref + " (no data property)");
                    }
                });

        assertThat(offenders)
                .as(
                        "every success response carrying a body must resolve to ApiResponse<T>"
                                + " with T declared")
                .isEmpty();
    }

    @Test
    void everyPagedSchemaDeclaresItsItemType() {
        JsonNode doc = document();
        List<String> offenders = new ArrayList<>();

        // Checked over components/schemas rather than by walking down from each operation, so a
        // page schema that is untyped is caught even while the envelope above it is also
        // under-declared. Walking from the operation would silently skip it in that state.
        doc.path("components")
                .path("schemas")
                .properties()
                .forEach(
                        entry -> {
                            JsonNode content = entry.getValue().path("properties").path("content");
                            if (!"array".equals(content.path("type").asString(""))) {
                                return;
                            }
                            JsonNode items = content.path("items");
                            if (items.isMissingNode() || items.isEmpty() || !items.has("$ref")) {
                                offenders.add(entry.getKey() + ".content.items unresolved");
                            }
                        });

        assertThat(offenders).as("every paged schema must declare a resolved item type").isEmpty();
    }

    @Test
    void everyReferencedSchemaExists() {
        JsonNode doc = document();
        Set<String> declared = new LinkedHashSet<>();
        doc.path("components").path("schemas").propertyNames().forEach(declared::add);

        Set<String> dangling = new LinkedHashSet<>();
        collectRefs(doc.path("paths"), declared, dangling);
        collectRefs(doc.path("components").path("schemas"), declared, dangling);

        assertThat(dangling)
                .as("every $ref must resolve to a member of components/schemas")
                .isEmpty();
    }

    @Test
    void everyCursorEndpointDeclaresA400Response() {
        JsonNode doc = document();
        Set<String> cursorOperations = new LinkedHashSet<>();

        // The cursor-endpoint set is derived the same way everyPagedSchemaDeclaresItsItemType
        // derives it: any operation whose success response resolves to a schema shaped like a
        // page (an array-typed content property alongside pageInfo), not a hardcoded path list.
        forEachSuccessResponseWithContent(
                doc,
                (operationId, code, mediaType, schema) -> {
                    if (isPagedSchema(doc, resolve(doc, schema))) {
                        cursorOperations.add(operationId);
                    }
                });

        List<String> offenders = new ArrayList<>();
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    if (cursorOperations.contains(operationId)
                            && !operation.path("responses").has("400")) {
                        offenders.add(operationId);
                    }
                });

        assertThat(offenders)
                .as("every cursor endpoint must declare a 400 response for a malformed cursor")
                .isEmpty();
    }

    @Test
    void everyCursorEndpointBoundsItsLimitParameter() {
        JsonNode doc = document();
        Set<String> cursorOperations = new LinkedHashSet<>();

        // Same derivation as everyCursorEndpointDeclaresA400Response: any operation whose success
        // response is page-shaped, not a hardcoded path list.
        forEachSuccessResponseWithContent(
                doc,
                (operationId, code, mediaType, schema) -> {
                    if (isPagedSchema(doc, resolve(doc, schema))) {
                        cursorOperations.add(operationId);
                    }
                });

        List<String> offenders = new ArrayList<>();
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    if (!cursorOperations.contains(operationId)) {
                        return;
                    }
                    JsonNode limitParam = null;
                    for (JsonNode param : operation.path("parameters")) {
                        if ("limit".equals(param.path("name").asString(""))) {
                            limitParam = param;
                            break;
                        }
                    }
                    if (limitParam == null) {
                        offenders.add(operationId + " (no limit parameter)");
                        return;
                    }
                    JsonNode schema = limitParam.path("schema");
                    boolean bounded =
                            schema.path("minimum").asInt(-1) == 1
                                    && schema.path("maximum").asInt(-1) == 100;
                    if (!bounded) {
                        offenders.add(operationId);
                    }
                });

        assertThat(offenders)
                .as("every cursor endpoint must bound its limit parameter to [1, 100]")
                .isEmpty();
    }

    /**
     * A cursor page schema sits one $ref past the ApiResponse envelope: {@code ApiResponse<T>.data}
     * points at the page schema itself, which carries an array-typed {@code content} property
     * alongside {@code pageInfo}. {@code resolve} only follows one $ref hop, so the envelope's
     * {@code data} property must be resolved a second time here.
     */
    private static boolean isPagedSchema(JsonNode doc, JsonNode resolvedEnvelope) {
        if (resolvedEnvelope == null) {
            return false;
        }
        JsonNode resolvedData = resolve(doc, resolvedEnvelope.path("properties").path("data"));
        if (resolvedData == null) {
            return false;
        }
        JsonNode content = resolvedData.path("properties").path("content");
        return "array".equals(content.path("type").asString(""))
                && resolvedData.path("properties").has("pageInfo");
    }

    @Test
    void everyBodyAcceptingEndpointDeclaresA400Response() {
        JsonNode doc = document();
        List<String> offenders = new ArrayList<>();

        // Derived from requestBody presence, not a hardcoded path list: any operation that
        // accepts a body can be sent a malformed one.
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    if (operation.path("requestBody").isMissingNode()) {
                        return;
                    }
                    if (!operation.path("responses").has("400")) {
                        offenders.add(operationId);
                    }
                });

        assertThat(offenders)
                .as("every body-accepting endpoint must declare a 400 response")
                .isEmpty();
    }

    @Test
    void anonymousOperationsDeclareAnEmptySecurityArray() {
        JsonNode doc = document();
        // The fixed set of operations the source annotates @Operation(security =
        // {@SecurityRequirement(name = "")}) to opt out of the global bearerAuth requirement.
        // Unlike the other tests in this class, this set cannot be derived from the document
        // itself: the document's `security` field is exactly what is under test, so deriving the
        // expected set from it would make the test vacuous.
        Set<String> anonymousOperations =
                Set.of(
                        "POST /api/v1/auth/register",
                        "POST /api/v1/auth/login",
                        "POST /api/v1/auth/refresh",
                        "GET /api/v1/auth/verify-email",
                        "POST /api/v1/auth/verify-email/resend",
                        "POST /api/v1/auth/forgot-password",
                        "POST /api/v1/auth/reset-password",
                        "POST /api/v1/auth/oauth2/exchange",
                        "GET /api/v1/hashtags/search",
                        "GET /api/v1/hashtags/trending",
                        "GET /api/v1/users/{userId}",
                        "GET /api/v1/users/by-username/{username}");

        List<String> offenders = new ArrayList<>();
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    if (!anonymousOperations.contains(operationId)) {
                        return;
                    }
                    JsonNode security = operation.path("security");
                    if (!security.isArray() || !security.isEmpty()) {
                        offenders.add(operationId + " -> " + security);
                    }
                });

        assertThat(offenders)
                .as("every anonymous operation must declare an empty security array")
                .isEmpty();
    }

    @Test
    void hashtagSearchDeclaresFlatQueryParametersNotAModelAttributeObject() {
        JsonNode doc = document();
        JsonNode parameters =
                doc.path("paths").path("/api/v1/hashtags/search").path("get").path("parameters");

        assertThat(parameters.isArray()).as("hashtag search must declare parameters").isTrue();
        Set<String> names = new LinkedHashSet<>();
        parameters.forEach(p -> names.add(p.path("name").asString("")));

        assertThat(names)
                .as("hashtag search must bind q/cursor/limit individually, not one object param")
                .containsExactlyInAnyOrder("q", "cursor", "limit");
    }

    @Test
    void everyAuthenticatedOperationDeclaresA401Response() {
        JsonNode doc = document();
        List<String> offenders = new ArrayList<>();

        // "Authenticated" is derived from the document's own security array being non-empty,
        // exactly the property AnonymousOperationSecurityCustomizer forces to [] for the 12
        // anonymous operations - so this set does not need a hardcoded path list.
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    JsonNode security = operation.path("security");
                    boolean requiresAuth = !security.isArray() || !security.isEmpty();
                    if (requiresAuth && !operation.path("responses").has("401")) {
                        offenders.add(operationId);
                    }
                });

        assertThat(offenders)
                .as("every authenticated operation must declare a 401 response")
                .isEmpty();
    }

    @Test
    void specificOperationsDeclareTheirObservedButUndocumentedStatus() {
        JsonNode doc = document();
        // Each pair names one status the audit observed on the wire but the document did not
        // list, confirmed against source as a real, reachable outcome (not the 500s HIGH-1/
        // HIGH-6/HIGH-7/MED-5 already account for).
        //
        // The four auth entries are the 403 UserStateValidator raises for a banned, suspended,
        // deactivated, or email-unverified account. The audit's authorisation matrix skips
        // /api/v1/auth/** by design, so this whole group went unmeasured there and was only
        // caught by re-running the harness end to end.
        record Expectation(String operationId, String status) {}
        List<Expectation> expectations =
                List.of(
                        new Expectation("POST /api/v1/auth/login", "403"),
                        new Expectation("POST /api/v1/auth/refresh", "403"),
                        new Expectation("POST /api/v1/auth/reset-password", "403"),
                        new Expectation("POST /api/v1/auth/oauth2/exchange", "403"),
                        new Expectation("GET /api/v1/posts/{postId}/comments", "403"),
                        new Expectation("GET /api/v1/comments/{commentId}/replies", "403"),
                        new Expectation("DELETE /api/v1/comments/{commentId}/like", "403"),
                        new Expectation("GET /api/v1/social/users/{userId}/followers", "403"),
                        new Expectation("GET /api/v1/social/users/{userId}/following", "403"),
                        new Expectation("POST /api/v1/social/block/{targetUserId}", "409"),
                        new Expectation("GET /api/v1/hashtags/trending", "400"));

        Map<String, JsonNode> operations = new java.util.HashMap<>();
        forEachOperation(doc, operations::put);

        List<String> offenders = new ArrayList<>();
        for (Expectation expectation : expectations) {
            JsonNode operation = operations.get(expectation.operationId());
            if (operation == null || !operation.path("responses").has(expectation.status())) {
                offenders.add(expectation.operationId() + " -> " + expectation.status());
            }
        }

        assertThat(offenders)
                .as("every confirmed observed-but-undocumented status must be declared")
                .isEmpty();
    }

    @Test
    void only422IsDocumentedForAKnownUnprocessableOutcome() {
        JsonNode doc = document();
        // 422 is reserved for a well-formed request whose content cannot be satisfied. Four
        // outcomes qualify: COMMENT_MODERATION_REJECTED, the media upload confirmation rejecting a
        // storage key with no matching stored object, a warning citing a reason key whose
        // report_reason_configs row is disabled or absent, and POST_BANNED_HASHTAG on the three
        // post write paths that publish a caption. All four are requests the server understood and
        // refused on their content. A 422 documented for anything else (e.g. generic
        // bean-validation failure, which the server answers with 400) is a contract defect, not a
        // valid outcome.
        Set<String> allowed422 =
                Set.of(
                        "POST /api/v1/posts/{postId}/comments",
                        "PATCH /api/v1/comments/{commentId}",
                        "POST /api/v1/media/upload-complete",
                        "POST /api/v1/admin/warnings/for-user/{userId}",
                        "POST /api/v1/posts",
                        "PATCH /api/v1/posts/{postId}",
                        "PATCH /api/v1/posts/{postId}/status");

        List<String> offenders = new ArrayList<>();
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    if (operation.path("responses").has("422")
                            && !allowed422.contains(operationId)) {
                        offenders.add(operationId);
                    }
                });

        assertThat(offenders)
                .as("422 must only be documented for a known unprocessable outcome")
                .isEmpty();
    }

    @Test
    void everyBodyAcceptingEndpointDeclaresA415Response() {
        JsonNode doc = document();
        // GlobalExceptionHandler answers a wrong Content-Type with 415 on every operation that
        // reads a request body, so every such operation must declare it. Derived from the
        // document's own requestBody presence rather than a hardcoded list, so a new body
        // endpoint that forgets it turns this red.
        List<String> offenders = new ArrayList<>();
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    if (operation.has("requestBody") && !operation.path("responses").has("415")) {
                        offenders.add(operationId);
                    }
                });

        assertThat(offenders)
                .as("every request-body endpoint must declare the 415 it returns")
                .isEmpty();
    }

    @Test
    void userProfileByIdDocumentsOnlyTheReachableReasonFor401() {
        JsonNode doc = document();
        // Two different 401s are easy to confuse here. The operation used to document one for
        // "target account is private", which no code path produces: assemblePublicProfile
        // returns 200 with the counter fields masked instead. It does return 401 when an
        // Authorization header is present but invalid, even though the endpoint is otherwise
        // anonymous, so the response stays documented - for that reason only.
        Map<String, JsonNode> operations = new java.util.HashMap<>();
        forEachOperation(doc, operations::put);

        JsonNode operation = operations.get("GET /api/v1/users/{userId}");
        assertThat(operation).isNotNull();
        JsonNode unauthorized = operation.path("responses").path("401");
        assertThat(unauthorized.isMissingNode())
                .as("the reachable invalid-token 401 must stay documented")
                .isFalse();
        assertThat(unauthorized.path("description").asString("").toLowerCase())
                .as("the 401 must not be attributed to the account being private")
                .doesNotContain("private");
    }

    @Test
    void observedNullFieldsAreDeclaredNullableInTheSchema() {
        JsonNode doc = document();
        // A sample of the audit's originally-cited observed-null fields, one per affected schema,
        // not an exhaustive re-check of all 79 fields marked across the branch: this is a contract
        // regression guard, not a re-derivation of the whole MED-3 audit.
        record Expectation(String schema, String field) {}
        List<Expectation> expectations =
                List.of(
                        new Expectation("PostResponse", "locationName"),
                        new Expectation("PostResponse", "latitude"),
                        new Expectation("NotificationResponse", "entityId"),
                        new Expectation("NotificationResponse", "entityType"),
                        new Expectation("NotificationResponse", "message"),
                        new Expectation("ConversationResponse", "lastMessageAt"),
                        new Expectation("CommentResponse", "parentId"),
                        new Expectation("CommentResponse", "rootId"),
                        new Expectation("ParticipantResponse", "leftAt"),
                        new Expectation("PageInfo", "startCursor"),
                        new Expectation("PageInfo", "endCursor"),
                        // The discipline and report-target payloads, whose declared-non-nullable
                        // fields were observed null against live responses: a strike issued by a
                        // since-deleted moderator, a warning nobody has revoked, and a comment
                        // target, which has no lifecycle status of its own.
                        new Expectation("AdminWarningViolationResponse", "actorId"),
                        new Expectation("AdminStrikeViolationResponse", "actorId"),
                        new Expectation("AdminWarnUserResponse", "strike"),
                        new Expectation("AdminWarningResponse", "revokedAt"),
                        new Expectation("AdminWarningResponse", "issuedBy"),
                        new Expectation("AdminStrikeResponse", "revokedAt"),
                        new Expectation("AdminStrikeResponse", "triggeredBy"),
                        new Expectation("AdminReportTargetResponse", "status"),
                        new Expectation("AdminReportTargetResponse", "ownerId"),
                        new Expectation("AdminReportTargetResponse", "ownerUsername"),
                        new Expectation("AdminReportTargetResponse", "text"));

        List<String> offenders = new ArrayList<>();
        JsonNode schemas = doc.path("components").path("schemas");
        for (Expectation expectation : expectations) {
            JsonNode property =
                    schemas.path(expectation.schema()).path("properties").path(expectation.field());
            if (property.isMissingNode()) {
                offenders.add(expectation.schema() + "." + expectation.field() + " -> not found");
                continue;
            }
            if (!isNullable(property)) {
                offenders.add(
                        expectation.schema() + "." + expectation.field() + " -> not nullable");
            }
        }

        assertThat(offenders)
                .as("every observed-null field must be declared nullable in the document")
                .isEmpty();
    }

    /**
     * OpenAPI 3.1 (this document's dialect) represents nullability as {@code type: [X, "null"]};
     * springdoc also still recognises the OpenAPI 3.0 {@code nullable: true} keyword on some schema
     * shapes, so both are accepted here rather than assuming one specific rendering.
     */
    private static boolean isNullable(JsonNode property) {
        if (property.path("nullable").asBoolean(false)) {
            return true;
        }
        JsonNode type = property.path("type");
        if (type.isArray()) {
            for (JsonNode element : type) {
                if ("null".equals(element.asString(""))) {
                    return true;
                }
            }
        }
        // A nullable object reference cannot carry its null in the type keyword, because a $ref
        // keeps its siblings under JSON Schema 2020-12 and the two assertions together are
        // unsatisfiable. The satisfiable form is a union with a null branch.
        for (JsonNode branch : property.path("oneOf")) {
            if ("null".equals(branch.path("type").asString(""))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void noNullableReferenceIsDeclaredAsAnUnsatisfiableSchema() {
        JsonNode doc = document();
        // springdoc renders @Schema(nullable = true) on an object-typed property as
        // {"type": "null", "$ref": "..."}. Under the dialect this document declares, a $ref keeps
        // its sibling keywords instead of replacing them, so that asserts the value is null and is
        // also the referenced object. Nothing satisfies both, and a generator reading it either
        // emits an impossible type or drops the nullability and hands back a non-optional field
        // that arrives null.
        List<String> offenders = new ArrayList<>();
        collectUnsatisfiableNullableRefs(doc.path("components").path("schemas"), "", offenders);

        assertThat(offenders)
                .as("a nullable reference must be a union, not a null-typed $ref")
                .isEmpty();
    }

    private static void collectUnsatisfiableNullableRefs(
            JsonNode node, String path, List<String> offenders) {
        if (node.isObject()) {
            if (node.has("$ref") && "null".equals(node.path("type").asString(""))) {
                offenders.add(path);
            }
            node.properties()
                    .forEach(
                            entry ->
                                    collectUnsatisfiableNullableRefs(
                                            entry.getValue(),
                                            path + "/" + entry.getKey(),
                                            offenders));
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node) {
                collectUnsatisfiableNullableRefs(element, path + "/" + index, offenders);
                index++;
            }
        }
    }

    @Test
    void closedSetRequestParametersAreEnumeratedNotDescribedInProse() {
        JsonNode doc = document();
        // A request parameter whose accepted values are a closed set must be declared as that set.
        // Declared as a bare string, the server still refuses everything outside it, so a client
        // discovers the set by guessing and a generator has no type to emit. The metric parameter
        // is the case the audit found: fourteen keys, named in the operation description where a
        // human can read them and a code generator cannot.
        JsonNode parameters =
                doc.path("paths")
                        .path("/api/v1/admin/stats/timeseries")
                        .path("get")
                        .path("parameters");
        assertThat(parameters.isArray()).as("timeseries must declare its parameters").isTrue();

        JsonNode metricSchema = null;
        for (JsonNode parameter : parameters) {
            if ("metric".equals(parameter.path("name").asString(""))) {
                metricSchema = parameter.path("schema");
            }
        }
        assertThat(metricSchema).as("the metric parameter must be declared").isNotNull();

        List<String> values = new ArrayList<>();
        metricSchema.path("enum").forEach(node -> values.add(node.asString("")));
        assertThat(values)
                .as("metric must enumerate every key PlatformMetric declares, and nothing else")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(PlatformMetric.values()).map(PlatformMetric::key).toList());
    }

    @Test
    void theDocumentPointsAtTheRealTimeSurfaceItCannotDescribe() {
        JsonNode doc = document();
        // OpenAPI has no vocabulary for a STOMP destination, so a consumer reading this document
        // alone found no mention of WebSockets at all and polled for the four domains that are
        // pushed. The document cannot describe the surface; it must at least name it and say where
        // it is described.
        String description = doc.path("info").path("description").asString("");
        assertThat(description)
                .as("the document must name the real-time surface it cannot describe")
                .contains("/ws/")
                .contains("ws-ticket")
                .contains("docs/modules/WEBSOCKET_GUIDE.md");
        assertThat(doc.path("externalDocs").path("url").asString(""))
                .as("and link to it")
                .isEqualTo("docs/modules/WEBSOCKET_GUIDE.md");
    }

    @Test
    void everyErrorCodeCarryingAPayloadHasAReachableDetailSchema() {
        JsonNode doc = document();
        // Two error codes reach ApiResponse.data with a non-null payload: POST_BANNED_HASHTAG and
        // VALIDATION_ERROR. ApiResponse.data is declared as an empty schema, which permits anything
        // and constrains nothing, so without a typed envelope a generated client hands back an
        // untyped value and the detail has to be parsed by hand.
        JsonNode schemas = doc.path("components").path("schemas");

        JsonNode bannedTags =
                schemas.path("BannedHashtagErrorResponse")
                        .path("properties")
                        .path("data")
                        .path("$ref");
        assertThat(bannedTags.asString(""))
                .as("the banned-hashtag envelope must type its data")
                .isEqualTo("#/components/schemas/BannedHashtagDetail");
        JsonNode detail = schemas.path("BannedHashtagDetail").path("properties").path("bannedTags");
        assertThat(detail.path("type").asString(""))
                .as("data.bannedTags must be reachable and typed")
                .isEqualTo("array");
        assertThat(detail.path("items").path("type").asString("")).isEqualTo("string");

        assertThat(schemas.has("ValidationErrorResponse"))
                .as("the validation envelope must be present even though no operation names it")
                .isTrue();
        assertThat(
                        schemas.path("ValidationErrorResponse")
                                .path("properties")
                                .path("data")
                                .path("additionalProperties")
                                .path("type")
                                .asString(""))
                .as("validation detail maps a field name to its message")
                .isEqualTo("string");

        // Every operation that documents the banned-hashtag 422 must name the typed envelope,
        // otherwise the detail is declared in one place and untyped in another.
        List<String> offenders = new ArrayList<>();
        forEachOperation(
                doc,
                (operationId, operation) -> {
                    JsonNode response = operation.path("responses").path("422");
                    if (response.isMissingNode()) {
                        return;
                    }
                    String ref =
                            response.path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asString("");
                    if (response.path("description").asString("").contains("banned hashtag")
                            && !ref.endsWith("/BannedHashtagErrorResponse")) {
                        offenders.add(operationId + " 422 -> " + ref);
                    }
                });
        assertThat(offenders)
                .as("a banned-hashtag 422 must declare the typed envelope, not the bare one")
                .isEmpty();
    }

    @Test
    void violationHistoryIsDeclaredAsADiscriminatedUnion() {
        JsonNode doc = document();
        // A violation page interleaves two row shapes. Declared as one flat schema, the fields
        // belonging to the other shape read as required and arrive null, and a generated client
        // has nothing to switch on. The document must therefore carry a real union: oneOf over
        // the two shapes plus a discriminator naming the property that tells them apart.
        JsonNode violation = doc.path("components").path("schemas").path("AdminViolationResponse");
        assertThat(violation.isMissingNode())
                .as("AdminViolationResponse must be present in the document")
                .isFalse();

        List<String> variants = new ArrayList<>();
        violation.path("oneOf").forEach(node -> variants.add(node.path("$ref").asString("")));
        assertThat(variants)
                .as("the violation payload must be a union of exactly the two row shapes")
                .containsExactlyInAnyOrder(
                        "#/components/schemas/AdminWarningViolationResponse",
                        "#/components/schemas/AdminStrikeViolationResponse");

        JsonNode discriminator = violation.path("discriminator");
        assertThat(discriminator.path("propertyName").asString(""))
                .as("the union must be discriminated on kind")
                .isEqualTo("kind");

        Map<String, String> mapping = new LinkedHashMap<>();
        discriminator
                .path("mapping")
                .properties()
                .forEach(entry -> mapping.put(entry.getKey(), entry.getValue().asString("")));
        assertThat(mapping)
                .as("each kind value must map to the shape it names")
                .containsOnly(
                        entry("warning", "#/components/schemas/AdminWarningViolationResponse"),
                        entry("strike", "#/components/schemas/AdminStrikeViolationResponse"));

        // Each variant must carry only the fields that shape actually has. The whole point of
        // splitting is that a strike stops declaring a reason key it never holds.
        JsonNode warningProperties =
                doc.path("components")
                        .path("schemas")
                        .path("AdminWarningViolationResponse")
                        .path("properties");
        JsonNode strikeProperties =
                doc.path("components")
                        .path("schemas")
                        .path("AdminStrikeViolationResponse")
                        .path("properties");
        assertThat(warningProperties.has("reasonKey")).isTrue();
        assertThat(warningProperties.has("note")).isTrue();
        assertThat(warningProperties.has("strikeNumber")).isFalse();
        assertThat(strikeProperties.has("strikeNumber")).isTrue();
        assertThat(strikeProperties.has("reasonKey")).isFalse();
        assertThat(strikeProperties.has("note")).isFalse();
    }

    @Test
    void noContentResponsesDeclareNoBody() {
        JsonNode doc = document();
        List<String> offenders = new ArrayList<>();

        forEachOperation(
                doc,
                (operationId, operation) ->
                        operation
                                .path("responses")
                                .properties()
                                .forEach(
                                        entry -> {
                                            if (!"204".equals(entry.getKey())) {
                                                return;
                                            }
                                            if (entry.getValue().has("content")) {
                                                // A 204 has no message body. Declaring a schema
                                                // for one invites a generator to emit a response
                                                // type that can never arrive.
                                                offenders.add(operationId + " 204");
                                            }
                                        }));

        assertThat(offenders).as("a 204 response must not declare a content block").isEmpty();
    }

    private interface ResponseVisitor {
        void visit(String operationId, String code, String mediaType, JsonNode schema);
    }

    private interface OperationVisitor {
        void visit(String operationId, JsonNode operation);
    }

    private void forEachOperation(JsonNode doc, OperationVisitor visitor) {
        doc.path("paths")
                .properties()
                .forEach(
                        pathEntry ->
                                pathEntry
                                        .getValue()
                                        .properties()
                                        .forEach(
                                                methodEntry -> {
                                                    if (!HTTP_METHODS.contains(
                                                            methodEntry.getKey())) {
                                                        return;
                                                    }
                                                    visitor.visit(
                                                            methodEntry.getKey().toUpperCase()
                                                                    + " "
                                                                    + pathEntry.getKey(),
                                                            methodEntry.getValue());
                                                }));
    }

    private void forEachSuccessResponseWithContent(JsonNode doc, ResponseVisitor visitor) {
        forEachOperation(
                doc,
                (operationId, operation) ->
                        operation
                                .path("responses")
                                .properties()
                                .forEach(
                                        responseEntry -> {
                                            String code = responseEntry.getKey();
                                            if (!code.startsWith("2")) {
                                                return;
                                            }
                                            JsonNode content =
                                                    responseEntry.getValue().path("content");
                                            content.properties()
                                                    .forEach(
                                                            mediaEntry ->
                                                                    visitor.visit(
                                                                            operationId,
                                                                            code,
                                                                            mediaEntry.getKey(),
                                                                            mediaEntry
                                                                                    .getValue()
                                                                                    .path(
                                                                                            "schema")));
                                        }));
    }

    private static String refName(JsonNode schema) {
        String ref = schema.path("$ref").asString("");
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? "" : ref.substring(slash + 1);
    }

    private static JsonNode resolve(JsonNode doc, JsonNode schema) {
        if (schema == null || schema.isMissingNode()) {
            return null;
        }
        if (!schema.has("$ref")) {
            return schema.isEmpty() ? null : schema;
        }
        JsonNode target = doc.path("components").path("schemas").path(refName(schema));
        return target.isMissingNode() ? null : target;
    }

    private static void collectRefs(JsonNode node, Set<String> declared, Set<String> dangling) {
        if (node.isObject()) {
            node.properties()
                    .forEach(
                            entry -> {
                                if ("$ref".equals(entry.getKey()) && entry.getValue().isString()) {
                                    String name = refName(node);
                                    if (!name.isEmpty() && !declared.contains(name)) {
                                        dangling.add(name);
                                    }
                                } else {
                                    collectRefs(entry.getValue(), declared, dangling);
                                }
                            });
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, declared, dangling));
        }
    }
}
