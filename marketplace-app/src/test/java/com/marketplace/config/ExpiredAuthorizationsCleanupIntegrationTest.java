package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.security.ExpiredAuthorizationsCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end guard for the self-maintenance layer, on the <b>real Flyway
 * schema</b> (the lesson recorded three times: a test schema built by
 * {@code ddl-auto: create-drop} hides what production actually runs — V24
 * revinfo, V28 archive table, V29 dead-job rows). Boots the full context on a
 * real PostgreSQL with {@code spring.flyway.enabled=true} +
 * {@code ddl-auto=none} (the {@code QuartzJdbcJobStoreConfigTest} pattern).
 *
 * <p>Guards two behaviors:
 * <ol>
 *   <li>{@link ExpiredAuthorizationsCleanup} deletes <i>only</i> fully-expired
 *       authorizations from {@code oauth2_authorization} (V13 schema) — the
 *       table Spring Authorization Server 7.1.1 never prunes by itself
 *       (framework source verified: {@code JdbcOAuth2AuthorizationService} has
 *       no scheduled or expiry-driven DELETE; {@code remove()} fires on
 *       explicit revocation only).</li>
 *   <li>The Application Module Actuator is exposed and secured: anonymous
 *       {@code GET /actuator/modulith} → 401, authenticated via a
 *       client_credentials token → 200 with the live module structure
 *       (official Modulith 2.1.1 "Production-ready Features" recipe).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ExpiredAuthorizationsCleanupIntegrationTest {

    private static final String MODULE_PATH = "/actuator/modulith";
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String CLIENT_ID = "marketplace-web-client";
    private static final String CLIENT_SECRET = "it-app-secret";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches QuartzJdbcJobStoreConfigTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExpiredAuthorizationsCleanup cleanup;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @AfterEach
    void cleanAuthorizationRows() {
        // V13 has no FK into oauth2_authorization; deleting by the test's id
        // prefix leaves the R__seed client and any other rows untouched.
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE id LIKE 'cleanup-it-%'");
    }

    @Test
    void purgeDeletesOnlyFullyExpiredAuthorizations() {
        Instant now = Instant.now();
        Instant tenDaysAgo = now.minus(Duration.ofDays(10));
        Instant eightDaysAgo = now.minus(Duration.ofDays(8));
        Instant oneDayAgo = now.minus(Duration.ofDays(1));
        Instant inOneDay = now.plus(Duration.ofDays(1));

        // A — fully expired 10 days ago (code + access + refresh all past): DELETED
        insertAuthorization("cleanup-it-A", tenDaysAgo, tenDaysAgo, tenDaysAgo);
        // B — access expired but refresh still valid: KEPT (live credential)
        insertAuthorization("cleanup-it-B", tenDaysAgo, tenDaysAgo, inOneDay);
        // C — no credential ever issued (all columns NULL): KEPT (conservative)
        insertAuthorization("cleanup-it-C", null, null, null);
        // D — fully expired but only 1 day ago, inside the 7-day retention: KEPT
        insertAuthorization("cleanup-it-D", oneDayAgo, oneDayAgo, oneDayAgo);
        // E — code + access expired 8 days ago, other credential columns NULL: DELETED
        insertAuthorization("cleanup-it-E", eightDaysAgo, eightDaysAgo, null);

        int deleted = cleanup.purgeExpiredBefore(now.minus(ExpiredAuthorizationsCleanup.RETENTION));

        assertThat(deleted).as("only the fully-expired rows A and E are deleted").isEqualTo(2);
        assertThat(rowExists("cleanup-it-A")).as("fully expired row deleted").isFalse();
        assertThat(rowExists("cleanup-it-E")).as("fully expired row (NULL credentials ignored) deleted").isFalse();
        assertThat(rowExists("cleanup-it-B")).as("row with a live refresh token survives").isTrue();
        assertThat(rowExists("cleanup-it-C")).as("all-NULL row survives (no lifecycle to evaluate)").isTrue();
        assertThat(rowExists("cleanup-it-D")).as("row inside the retention window survives").isTrue();
    }

    @Test
    void modulithEndpointIsExposedAndSecured() throws Exception {
        // Anonymous → 401: module topology falls under the authenticated()
        // default of SecurityConfig's /actuator/** rules (not permitAll).
        HttpResponse<String> anonymous = get(MODULE_PATH, null);
        assertThat(anonymous.statusCode())
                .as("anonymous /actuator/modulith must be rejected: %s", anonymous.body())
                .isEqualTo(401);

        // client_credentials grant (registered by OAuth2ClientSecretInitializer —
        // AuthorizationGrantType.CLIENT_CREDENTIALS) — the customizer stamps the
        // resource-server audience on every access token, so this JWT passes
        // SecurityConfigJwtDecoder's audience validator.
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> tokenResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + TOKEN_PATH))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .header("Authorization", "Basic " + credentials)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode())
                .as("client_credentials token: %s", tokenResponse.body())
                .isEqualTo(200);
        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        String accessToken = tokens.path("access_token").asString();

        // Authenticated → 200 with the live module structure. The JSON is keyed
        // by module names (official docs table: "$.{moduleName}"); catalog is
        // one of this application's modules (SYSTEM.md §5).
        HttpResponse<String> modulith = get(MODULE_PATH, accessToken);
        assertThat(modulith.statusCode())
                .as("authenticated /actuator/modulith: %s", modulith.body())
                .isEqualTo(200);
        assertThat(modulith.body())
                .as("the live module structure names the catalog module")
                .contains("catalog");
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private boolean rowExists(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * Inserts a minimal authorization row on the real V13 schema. Only the
     * lifecycle columns under test are set: authorization code, access token
     * and refresh token expiries; oidc/user/device code columns stay NULL
     * (never issued). V13 declares no FK on registered_client_id, so no client
     * row is needed for the purge predicate.
     */
    private void insertAuthorization(String id, Instant codeExpiresAt, Instant accessExpiresAt,
                                     Instant refreshExpiresAt) {
        jdbcTemplate.update(
                "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, "
                        + "authorization_grant_type, authorization_code_expires_at, "
                        + "access_token_expires_at, refresh_token_expires_at) VALUES (?,?,?,?,?,?,?)",
                id, "cleanup-it-client", "cleanup-it-principal", "authorization_code",
                codeExpiresAt == null ? null : java.sql.Timestamp.from(codeExpiresAt),
                accessExpiresAt == null ? null : java.sql.Timestamp.from(accessExpiresAt),
                refreshExpiresAt == null ? null : java.sql.Timestamp.from(refreshExpiresAt));
    }
}
