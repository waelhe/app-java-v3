package com.marketplace.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L40 (realestate systems plan §5 — view analytics): acceptance criterion
 * 3 — the anonymous visitor is counted WITHOUT storing a permanent
 * identifier: "اختبار عدم وجود مفتاح Redis بعد انتهاء TTL اليوم".
 *
 * <p>This class carries its own context (the property exists for exactly
 * this criterion): the dedup window is overridden from the production
 * 24h to 200 milliseconds, so the TTL expiry runs in the test's own
 * clock instead of a day. The three measured facts:
 * <ol>
 *   <li>inside the window, the marker EXISTS and the dedup answers
 *       "already counted" (no second +1);</li>
 *   <li>after the window, the marker is GONE (Redis actually expired it
 *       — the privacy statement's mechanism, measured not assumed);</li>
 *   <li>after the window, the SAME visitor counts again (a new rolling
 *       window began) — and the only stored trace remains the anonymous
 *       daily count row: no identifier, no profile, nothing else.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The TTL-expiry seam: the production default is the plan's 24h
        // ("Redis TTL يوم"); 2s here makes the criterion runnable. The
        // window must comfortably exceed TWO sequential MockMvc reads —
        // the first performs the locked insert + the Envers revision
        // against the container database, which on a slow CI runner can
        // take longer than a sub-second window (CodeRabbit round 1,
        // adopted — the flake class this avoids).
        "marketplace.catalog.views.dedup-window=2s",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ListingViewsDedupTtlIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; connection details via RedisContainerConnectionDetailsFactory
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CatalogProperties catalogProperties;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final String VISITOR_IP = "203.0.113.7";

    @BeforeEach
    void seed() {
        clearDedupMarkers();
        jdbcTemplate.update("DELETE FROM listing_views_daily_aud");
        jdbcTemplate.update("DELETE FROM listing_views_daily");
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id = ?", LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id = ?", OWNER_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", OWNER_USER_ID);

        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                OWNER_USER_ID, "l40ttl-" + OWNER_USER_ID, "l40ttl-" + OWNER_USER_ID + "@example.com",
                "L40 TTL User " + OWNER_USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L40 TTL Provider', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), OWNER_USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, 'L40 TTL Listing', 'seed', 'home', 1000, 'SAR', 'ACTIVE', now(), now(), 0, false)
                """,
                LISTING_ID, OWNER_USER_ID);

        // the property override is the measured premise of this guard
        assertThat(catalogProperties.views().dedupWindow())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @AfterEach
    void cleanUp() {
        clearDedupMarkers();
        jdbcTemplate.update("DELETE FROM listing_views_daily_aud");
        jdbcTemplate.update("DELETE FROM listing_views_daily");
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id = ?", LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id = ?", OWNER_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", OWNER_USER_ID);
    }

    @Test
    void markerExpiresAndTheVisitorCountsAgain_noPermanentIdentifierStored() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        // 1) inside the window: the marker exists, dedup answers "counted"
        readAs(VISITOR_IP);
        readAs(VISITOR_IP);
        assertThat(countedToday()).isEqualTo(1L);
        assertThat(dedupMarkerKeys()).hasSize(1);

        // 2) after the TTL: the marker is GONE — Redis expired it (the
        // criterion's own wording: "عدم وجود مفتاح Redis بعد انتهاء TTL")
        // (2.5s > the 2s window, with margin for Redis's lazy expiry)
        Thread.sleep(2_500);

        assertThat(dedupMarkerKeys()).isEmpty();

        // 3) the same visitor counts again — a new rolling window began;
        // and the stored trace stays ONE anonymous count row (no
        // identifier anywhere: the fingerprint lived only inside the
        // expired key)
        readAs(VISITOR_IP);
        assertThat(countedToday()).isEqualTo(2L);
        assertThat(dedupMarkerKeys()).hasSize(1);

        // the ONLY rows this visitor produced: the daily count (anonymous
        // by construction — no sender id, no fingerprint column exists)
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listing_views_daily WHERE listing_id = ?", Integer.class, LISTING_ID);
        assertThat(rows).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // helpers

    private void readAs(String remoteAddr) throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", LISTING_ID)
                        .with(request -> {
                            request.setRemoteAddr(remoteAddr);
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    private long countedToday() {
        Long counted = jdbcTemplate.queryForObject(
                "SELECT view_count FROM listing_views_daily WHERE listing_id = ? AND view_date = ?",
                Long.class, LISTING_ID, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
        return counted == null ? 0L : counted;
    }

    private Set<String> dedupMarkerKeys() {
        Set<String> keys = redisTemplate.keys(ListingViewCounter.DEDUP_KEY_PREFIX + "*");
        return keys == null ? Set.of() : keys;
    }

    private void clearDedupMarkers() {
        Set<String> keys = redisTemplate.keys(ListingViewCounter.DEDUP_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
