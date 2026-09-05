package com.marketplace.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for the cold-cache Redis value serialization path — the
 * live defect found while preparing the load-test baseline (roadmap A5):
 * the Redis cache stores values with Spring Boot's default
 * {@code JdkSerializationRedisSerializer} (spring-boot-cache 4.1.1
 * {@code RedisCacheConfiguration}), but every cached type —
 * {@code Page<ListingSummary>} (catalog ×3 + search), seven entities
 * extending {@code BaseEntity} (reviews, bookings, users ×2, conversations,
 * paymentIntents, providers) and {@code PricingService.PriceBreakdown} —
 * was non-Serializable, so every cold-cache PUT threw
 * {@code NotSerializableException}, which propagates through the cache
 * interceptor and fails the whole request with HTTP 500
 * {@code INT-001} (live-proven: {@code GET /api/v1/listings} → 500 on a
 * fresh Redis, after every TTL expiry, in production shape).
 *
 * <p><b>Why CI never saw it:</b> the test profile forces
 * {@code spring.cache.type=simple} — the in-memory cache stores object
 * references and never serializes, so no test exercised the Redis value
 * path even though {@code CacheRedisTtlIntegrationTest} ran against a real
 * Redis (its probe value is a {@code String}, trivially Serializable).
 * The lesson: <i>the serialization seam is only exercised by a cold cache
 * holding a real cached type</i>.
 *
 * <p>Guards both cached-type families through the real HTTP surface
 * (anonymous permitAll routes):
 * <ol>
 *   <li><b>{@code Page<ListingSummary>}</b> — {@code GET /api/v1/listings}
 *       on a cold cache: first request is the miss → PUT (the defect site),
 *       second request is the Redis HIT. Both must be 200.</li>
 *   <li><b>Entity ({@code Review extends BaseEntity})</b> —
 *       {@code GET /api/v1/reviews/{id}} cold miss → entity PUT, then the
 *       database row is deleted out from under the cache (raw JDBC, no
 *       AFTER_COMMIT relay fires — invalidation is documented to fire only
 *       when an entity changes through the application), and the second
 *       GET must still return 200: the only possible source is the
 *       deserialized Redis entry.</li>
 * </ol>
 *
 * <p>Setup follows {@code CacheRedisTtlIntegrationTest}: full application
 * context on real PostgreSQL (Flyway enabled, {@code ddl-auto=none},
 * {@code postgres:18-alpine}) + an isolated {@code redis:8-alpine}
 * container via {@code @ServiceConnection}, cache type forced to
 * {@code redis} (the test profile otherwise overrides it to
 * {@code simple}). Seeds follow the {@code CatalogSearchFullTextIntegrationTest}
 * pattern (JdbcTemplate parent rows first — the migration schema enforces
 * the FKs that create-drop masked).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The test profile overrides the cache type to 'simple' — force the
        // production type so this guard exercises the real Redis path.
        "spring.cache.type=redis",
        // The exact key and value the base application.yml declares (pinned
        // by CacheTtlConfigTest). Bound by CacheProperties$Redis.timeToLive.
        "spring.cache.redis.time-to-live=1h",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ColdCacheRedisSerializationIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** FK parents (V2: provider_listings.provider_id → users; V3: bookings ×3 FKs). */
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID CONSUMER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();

    @BeforeEach
    void seedAndClearCaches() {
        // A warm cache from a previous test method would serve the page
        // without ever reaching the PUT — the defect site. Clear so every
        // test starts cold (the CatalogSearchFullTextIntegrationTest pattern).
        cacheManagerProvider.ifAvailable(cm ->
                cm.getCacheNames().forEach(name -> {
                    var cache = cm.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));

        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_ID, "cold-cache-provider@example.com",
                "cold-cache-provider@example.com", "Cold Cache Provider");
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """,
                CONSUMER_ID, "cold-cache-consumer@example.com",
                "cold-cache-consumer@example.com", "Cold Cache Consumer");
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                LISTING_ID, PROVIDER_ID, "Cold Cache Garden View",
                "Apartment overlooking a garden", "home", 100_00L);
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency)
                VALUES (?, ?, ?, ?, 'COMPLETED', ?, 'SAR')
                ON CONFLICT (id) DO NOTHING
                """,
                BOOKING_ID, CONSUMER_ID, PROVIDER_ID, LISTING_ID, 100_00L);
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment)
                VALUES (?, ?, ?, ?, 5, 'Cold cache round trip')
                ON CONFLICT (id) DO NOTHING
                """,
                REVIEW_ID, BOOKING_ID, CONSUMER_ID, PROVIDER_ID);
    }

    @Test
    void catalogPageBrowseSurvivesTheColdCache() throws Exception {
        // First request = cold miss → the @Cacheable PUT that used to throw
        // NotSerializableException (PageImpl is Serializable — the record
        // was not) and fail the whole request with HTTP 500.
        HttpResponse<String> first = get("/api/v1/listings?page=0&size=10");
        assertThat(first.statusCode())
                .as("cold-cache browse (miss → PUT): %s", first.body())
                .isEqualTo(200);

        // Second request = Redis HIT (deserialize the stored page).
        HttpResponse<String> second = get("/api/v1/listings?page=0&size=10");
        assertThat(second.statusCode())
                .as("warm browse (Redis HIT): %s", second.body())
                .isEqualTo(200);
        assertThat(second.body())
                .as("the cached page round-trips with its content")
                .contains("Cold Cache Garden View");
    }

    @Test
    void cachedEntityRoundTripsThroughRedis() throws Exception {
        // Cold miss → entity PUT (BaseEntity was non-Serializable — the
        // same 500 family for the seven entity caches).
        HttpResponse<String> first = get("/api/v1/reviews/" + REVIEW_ID);
        assertThat(first.statusCode())
                .as("cold-cache entity read (miss → PUT): %s", first.body())
                .isEqualTo(200);

        // Remove the database row behind the cache's back: raw JDBC delete
        // fires no AFTER_COMMIT relay (invalidation is documented to fire
        // only when an entity changes through the application), so the
        // cache entry survives. Reviews has no referencing FK.
        jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", REVIEW_ID);

        // The only possible source for a 200 now is the deserialized Redis
        // entry — proving the entity value round-trip.
        HttpResponse<String> second = get("/api/v1/reviews/" + REVIEW_ID);
        assertThat(second.statusCode())
                .as("entity read after its DB row is deleted must come from Redis: %s", second.body())
                .isEqualTo(200);
        assertThat(second.body())
                .as("the deserialized entity keeps its content")
                .contains("Cold cache round trip");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
