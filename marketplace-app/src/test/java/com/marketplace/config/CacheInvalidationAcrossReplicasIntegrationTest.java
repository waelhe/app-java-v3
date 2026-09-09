package com.marketplace.config;

import com.marketplace.MarketplaceApplication;
import com.marketplace.catalog.CatalogService;
import com.marketplace.catalog.ProviderListing;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cache.CacheManager;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I5 (internal free plan §5 — audit gap 3): the cross-replica cache
 * INVALIDATION guard — the piece Layer 14 did not cover. The L14 test proved
 * the shared ENTRY (an entry cached by replica A is served by replica B);
 * this guard proves the shared EVICTION: a transactional write on replica A
 * (which publishes {@code CacheInvalidationRequested} and runs the
 * AFTER_COMMIT {@code CacheInvalidationRelay} in A's JVM) removes the entry
 * from the SHARED Redis cache — replica B's next read misses and re-reads
 * from the database.
 *
 * <p><b>The design fact this guard pins (the plan rule §10.3 deviation,
 * documented in the PR):</b> the production cache is
 * {@code cache.type=redis} ({@code application-prod.yml:31-32}) — ONE shared
 * cache store for every replica. The official cache-abstraction contract
 * (Spring Framework Reference — Understanding the Cache Abstraction, quoted
 * in {@code CacheInvalidationRelay}'s javadoc): "The caching abstraction has
 * no special handling for multi-threaded and multi-process environments, as
 * such features are handled by the cache implementation" — the Redis
 * implementation IS the multi-process mechanism: evict on A = DEL on the
 * shared key = a miss for B. No pub/sub relay is needed on top of the
 * shared store; the audit gap's threat model (per-instance local caches)
 * does not match the measured production topology. The guard keeps this
 * true: if the topology ever changes to local caches, this test fails and
 * THAT change carries its own cross-instance invalidation design.
 *
 * <p><b>The flow under test (the plan's own acceptance):</b>
 * «إبطال منشأ في النسخة (أ) يظهر أثره في (ب)» — (1) replica A's browse PUT
 * warms the shared entry; (2) replica B is served the SAME (stale-titled)
 * entry — sharing, the warm-up fact; (3) a REAL transactional update on
 * replica A (the service write — {@code CatalogService.update}) publishes
 * the event, the relay evicts AFTER_COMMIT; (4) replica B's next browse
 * shows the NEW title — only a miss-and-reload can produce it. Plus
 * «فشل الناشر لا يُسقط الطلب الأصلي»: the write call itself returns the
 * updated entity — the eviction runs after the transaction, by design.
 *
 * <p><b>Setup</b> mirrors {@code MultiReplicaReadinessIntegrationTest}
 * (Layer 14): context A on RANDOM_PORT + context B launched manually in
 * {@code @BeforeAll} against the SAME PostgreSQL + Redis containers, both
 * with {@code cache.type=redis} (the production type), the shared JKS
 * generated with the JDK's own {@code keytool} (the production key channel
 * — {@code SecurityConfig.jwkSource} requires a certificate entry).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The test profile overrides the cache type to 'simple' — force the
        // production type so both replicas exercise the real shared Redis path.
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=1h",
        // CI connection budget — the same rationale as the L14 harness: both
        // replicas stay light against the shared 100-connection PostgreSQL.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CacheInvalidationAcrossReplicasIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; connection details via RedisContainerConnectionDetailsFactory
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    /** Replica B: a second full application context on its own random port. */
    private static ConfigurableApplicationContext replicaB;
    private static int portB;

    /** The shared JKS both replicas sign with (the production key channel). */
    private static Path keystoreFile;

    @DynamicPropertySource
    static void sharedKeystoreProperties(DynamicPropertyRegistry registry) {
        // Both replicas must sign with the SAME key — the production channel
        // (keys/README.md). Context A reads these lazily when its context
        // boots; context B receives the same values as command-line args.
        if (keystoreFile != null) {
            registry.add("marketplace.security.jwt.keystore.path", () -> keystoreFile.toString());
            registry.add("marketplace.security.jwt.keystore.password", () -> "itstore");
            registry.add("marketplace.security.jwt.keystore.alias", () -> "itjwt");
            registry.add("marketplace.security.jwt.keystore.key-password", () -> "itjwtkey");
        }
    }

    @BeforeAll
    static void startReplicaB() throws Exception {
        // The Testcontainers extension (BeforeAllCallback) has already started
        // the shared PostgreSQL + Redis. Generate the shared keystore first.
        keystoreFile = Files.createTempFile("i5-invalidation-it", ".jks");
        Files.delete(keystoreFile); // keytool refuses to overwrite an existing (empty) file
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString(),
                "-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-alias", "itjwt",
                "-keystore", keystoreFile.toString(), "-storetype", "JKS",
                "-storepass", "itstore", "-keypass", "itjwtkey",
                "-dname", "CN=i5-invalidation-it", "-validity", "30")
                .redirectErrorStream(true)
                .start();
        String keytoolOutput = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as("keytool must generate the shared JKS: %s", keytoolOutput).isZero();

        // Command-line args are the highest-precedence property source: they
        // override the test profile's cache.type=simple / flyway disabled /
        // ddl create-drop and its localhost datasource, so replica B runs in
        // exactly the production-shaped configuration against the SAME stores.
        replicaB = new SpringApplicationBuilder(MarketplaceApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.cache.type=redis",
                        "--spring.datasource.hikari.maximum-pool-size=5",
                        "--spring.datasource.hikari.minimum-idle=1",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--marketplace.security.jwt.keystore.path=" + keystoreFile,
                        "--marketplace.security.jwt.keystore.password=itstore",
                        "--marketplace.security.jwt.keystore.alias=itjwt",
                        "--marketplace.security.jwt.keystore.key-password=itjwtkey");
        portB = ((WebServerApplicationContext) replicaB).getWebServer().getPort();
        assertThat(portB).as("replica B must be listening on its own port").isPositive();
    }

    @AfterAll
    static void stopReplicaB() throws Exception {
        if (replicaB != null) {
            replicaB.close();
        }
        // CodeRabbit #270 nitpick (adopted): the generated JKS holds an RSA
        // private key — do not let it accumulate in the temp directory.
        if (keystoreFile != null) {
            Files.deleteIfExists(keystoreFile);
        }
    }

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogService catalogService;

    @Value("${local.server.port}")
    private int portA;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** FK parents (V2: provider_listings.provider_id → users). */
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final String TITLE_BEFORE = "I5 Invalidation Villa — before";
    private static final String TITLE_AFTER = "I5 Invalidation Villa — after the write";

    @Test
    @WithMockUser(roles = {"PROVIDER", "ADMIN"})
    void invalidationOnReplicaA_propagatesToReplicaB_throughTheSharedCache() throws Exception {
        // Cold start: a warm entry from an earlier test method would answer
        // the first request from the cache and the PUT would never happen
        // (the ColdCache lesson — the L14 cold-start round).
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        seedListingRow(TITLE_BEFORE);

        // (1) Replica A: cold miss -> the @Cacheable PUT into the SHARED Redis.
        HttpResponse<String> warmUp = getJson(portA, "/api/v1/listings?page=0&size=10");
        assertThat(warmUp.statusCode()).as("cold browse on replica A: %s", body(warmUp)).isEqualTo(200);
        assertThat(body(warmUp)).contains(TITLE_BEFORE);

        // (2) Replica B is served the SAME shared entry (the warm-up fact —
        // L14 already pins sharing; this phase makes the staleness visible:
        // without any eviction, B keeps answering the warm entry).
        HttpResponse<String> fromShared = getJson(portB, "/api/v1/listings?page=0&size=10");
        assertThat(fromShared.statusCode()).as("warm browse on replica B: %s", body(fromShared)).isEqualTo(200);
        assertThat(body(fromShared)).contains(TITLE_BEFORE);

        // (3) The REAL transactional write ON REPLICA A — the service path
        // that publishes CacheInvalidationRequested and runs the AFTER_COMMIT
        // relay in A's JVM. The method PARAMETER is the production principal
        // shape (a JwtAuthenticationToken whose subject resolves to the
        // seeded provider's users row — the CI round-1 lesson: the REAL
        // IdentityUserProvider accepts only JWT tokens, "Unsupported
        // authentication type" otherwise) with ROLE_ADMIN so ownership is
        // bypassed after resolution; the method-security gate reads the
        // @WithMockUser context. The write itself is the production code
        // path under test, and the call returning the updated entity is
        // ALSO the «فشل الناشر لا يُسقط الطلب الأصلي» fact: the eviction
        // runs after the transaction, by design.
        Authentication admin = jwtAuthentication("i5-invalidation-provider@example.com");
        ProviderListing updated = catalogService.update(LISTING_ID, TITLE_AFTER,
                "Cross-instance invalidation proof listing", "home", 100_00L, null, admin);
        assertThat(updated.getTitle()).isEqualTo(TITLE_AFTER);

        // (4) Replica B's next browse shows the NEW title — only a MISS and
        // a reload from the database can produce it: A's relay evicted the
        // SHARED entry (a stale answer here would mean the eviction stayed
        // local to A — the exact audit-gap-3 threat this guard closes).
        awaitFreshTitleOnReplicaB();
    }

    /**
     * Plain poll loop (30s / 500ms) — no Awaitility dependency in this
     * reactor. B's cached answer only turns fresh after the eviction lands;
     * polling tolerates the AFTER_COMMIT dispatch's async window.
     */
    private void awaitFreshTitleOnReplicaB() throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        String last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = getJson(portB, "/api/v1/listings?page=0&size=10");
            last = body(response);
            if (response.statusCode() == 200 && last.contains(TITLE_AFTER)
                    && !last.contains(TITLE_BEFORE)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError(String.format(
                "Replica B never saw the post-eviction fresh title %s (last seen: %s) — "
                        + "the invalidation originating on replica A did not propagate through "
                        + "the shared Redis cache.",
                TITLE_AFTER, last));
    }

    private void seedListingRow(String title) {
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_ID, "i5-invalidation-provider@example.com",
                "i5-invalidation-provider@example.com", "I5 Invalidation Provider");
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title
                """,
                LISTING_ID, PROVIDER_ID, title,
                "Cross-instance invalidation proof listing", "home", 100_00L);
    }

    /**
     * The method-parameter principal: a {@code JwtAuthenticationToken} whose
     * subject is a real users row — the production resource-server shape the
     * real {@code IdentityUserProvider} resolves (the ReviewsTwoWay pattern).
     */
    private static org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuthentication(
            String subject) {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
        return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                jwt, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // -- HTTP helpers (the MultiReplicaReadinessIntegrationTest shape) --

    private HttpResponse<String> getJson(int port, String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + (path.startsWith("/") ? path : "/" + path)))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body();
    }
}
