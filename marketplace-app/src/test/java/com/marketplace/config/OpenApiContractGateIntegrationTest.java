package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.IntegrationContainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.7 (compliance plan §6 wave B) — the declared CI gate "فحص diff في CI": the
 * OpenAPI contract surface cannot change silently. The gate boots the real
 * application (real PostgreSQL + real Redis + real Flyway schema — the house
 * IT harness), fetches the live specification springdoc serves at
 * {@code /v3/api-docs} (GET is permitAll — the public contract surface), and
 * diffs it against the committed golden record
 * {@code openapi-contract-surface.json}.
 *
 * <p><b>Gate semantics (breaking-only, the additive-only philosophy of the
 * parallel contracts ledger):</b>
 * <ul>
 *   <li>An operation the golden records that is <b>absent from the live spec
 *       (or changed HTTP method)</b> breaks the gate — clients calling the
 *       recorded URL+method stop being served. The failure lists every broken
 *       entry precisely.</li>
 *   <li><b>Additive</b> operations (present live, absent golden) pass — new
 *   surface is the documented additive-only growth — and are reported in the
 *   test log as the refresh candidates for the golden.</li>
 *   <li>{@code operationId} drift on surviving operations is <b>logged, not
 *   failed</b>: operationId is documentation metadata springdoc derives from
 *   method names (measured: auto-generated with duplicate-count suffixes like
 *   {@code delete_7}), so a rename or suffix shift is spec-visible for review
 *   but breaks no client.</li>
 * </ul>
 *
 * <p>The golden was measured from the live production deployment
 * (2026-10-08, 157 paths / 189 operations) — the deployed contract is the
 * source of truth this gate defends. Updating the golden is a deliberate,
 * same-commit act: the file reads like a changelog, so its git history IS
 * the contract's change log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple').
        "spring.cache.type=redis",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OpenApiContractGateIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCommittedContractSurfaceSurvivesInTheLiveSpec() throws Exception {
        Map<String, String> golden = goldenSurface();
        Map<String, String> live = liveSurface();

        assertThat(golden).as("the golden record must load (157 paths / 189 operations measured)").isNotEmpty();
        assertThat(live).as("the live spec must load (/v3/api-docs)").isNotEmpty();

        // The gate: every recorded operation must survive with the same
        // HTTP method at the same path. Anything else is a breaking change.
        Map<String, String> broken = new TreeMap<>();
        for (Map.Entry<String, String> entry : golden.entrySet()) {
            String liveOperationId = live.get(entry.getKey());
            if (liveOperationId == null) {
                broken.put(entry.getKey(), "absent from the live spec");
            } else if (!liveOperationId.equals(entry.getValue())) {
                // Documentation-metadata drift on a SURVIVING operation: no
                // client breaks — logged for review, never a gate failure
                // (springdoc's auto ids carry duplicate-count suffixes).
                System.out.printf(
                        "[openapi-contract] operationId drift (non-breaking, review): %s golden='%s' live='%s'%n",
                        entry.getKey(), entry.getValue(), liveOperationId);
            }
        }
        assertThat(broken)
                .as("breaking OpenAPI contract changes: every listed operation disappeared or changed method. "
                        + "If the break is intentional, update openapi-contract-surface.json in the SAME commit "
                        + "and describe the decision there — the gate exists so this can never land silently.")
                .isEmpty();

        // The additive delta: new surface passes (additive-only growth) and
        // is reported as the golden's refresh candidates.
        Map<String, String> additive = new TreeMap<>(live);
        golden.keySet().forEach(additive.keySet()::remove);
        System.out.printf("[openapi-contract] live surface: %d operations; golden: %d; additive (refresh candidates): %d%n",
                live.size(), golden.size(), additive.size());
        additive.forEach((key, operationId) ->
                System.out.printf("[openapi-contract] additive: %s -> %s%n", key, operationId));
    }

    private Map<String, String> liveSurface() throws Exception {
        // GET /v3/api-docs is permitAll (the public contract surface — the
        // same surface the production smoke measures).
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET /v3/api-docs status").isEqualTo(200);
        String body = response.body();
        JsonNode paths = mapper.readTree(body).path("paths");
        Map<String, String> surface = new TreeMap<>();
        paths.propertyNames().forEach(path -> {
            JsonNode item = paths.path(path);
            for (String method : new String[] { "get", "post", "put", "delete", "patch", "head", "options" }) {
                if (item.has(method)) {
                    surface.put(method.toUpperCase() + " " + path, item.path(method).path("operationId").asString(""));
                }
            }
        });
        return surface;
    }

    private Map<String, String> goldenSurface() throws Exception {
        try (var stream = getClass().getResourceAsStream("/openapi-contract-surface.json")) {
            assertThat(stream).as("the committed golden record").isNotNull();
            JsonNode operations = mapper.readTree(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .path("operations");
            Map<String, String> surface = new TreeMap<>();
            operations.propertyNames().forEach(key ->
                    surface.put(key, operations.path(key).asString("")));
            return surface;
        }
    }
}
