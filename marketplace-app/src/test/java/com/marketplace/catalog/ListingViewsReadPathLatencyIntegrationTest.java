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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L40 (realestate systems plan §5 — view analytics): the D-E7 measurement
 * — "قياس p95 في PR L40 نفسه — إن تجاوز، الحدث داخل نفس PR". The debt's
 * question: does the counting write slow the hot public read measurably?
 *
 * <p><b>Method (two cohorts, one environment, one clock):</b>
 * <ul>
 *   <li>Cohort A — the DEDUP HIT path: every read from the SAME visitor;
 *       the read itself plus one Redis SETNX miss. No database write.</li>
 *   <li>Cohort B — the FULL COUNTING path: every read from a UNIQUE
 *       visitor; the read plus the Redis marker plus the locked +1 (the
 *       pessimistic read-modify-write, the Envers revision INSERT and the
 *       commit) — the hot-listing worst case, since all 120 increments
 *       claim the SAME (listing, today) row and serialize on its lock.</li>
 * </ul>
 * Both cohorts dispatch through the REAL MockMvc chain (filter chain,
 * controller, counter, Redis, PostgreSQL) — the network/TLS layer outside
 * MockMvc is identical for both cohorts, so the p95 DIFFERENCE isolates
 * the counting write's marginal cost. That difference is the number this
 * test prints and the number the D-E7 closure cites.
 *
 * <p><b>The gate:</b> p95(B) − p95(A) &lt; 150 ms. Typical measured values
 * are single-digit milliseconds (one extra statement batch per read); the
 * bound is deliberately generous for CI container noise while still
 * catching the disasters it exists for — a missing index turning every
 * read into a seq scan, or lock contention escalating to waits. If the
 * bound ever trips, the plan's own instruction applies: move the write to
 * a Modulith event INSIDE the same PR (D-E7's closure point).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The measurement context lifts the catalog rate limiter (50/min
        // in the base profile): a THROTTLED read measures permit-wait
        // time, not work time — the D-E7 question is the marginal cost of
        // the counting write per read, so the 260 measurement reads must
        // never queue behind the limiter. Production keeps its own limit;
        // the limiter's own guards live elsewhere (ResilienceAnnotationTest
        // pins the annotation; the rate-limit behavior tests run their own
        // contexts).
        "resilience4j.ratelimiter.instances.catalog.limit-for-period=1000",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ListingViewsReadPathLatencyIntegrationTest {

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

    private static final int WARMUP = 20;
    private static final int COHORT = 120;
    private static final long P95_DIFF_BOUND_MS = 150;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();

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
                OWNER_USER_ID, "l40p95-" + OWNER_USER_ID, "l40p95-" + OWNER_USER_ID + "@example.com",
                "L40 P95 User " + OWNER_USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L40 P95 Provider', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), OWNER_USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, 'L40 P95 Listing', 'seed', 'home', 1000, 'SAR', 'ACTIVE', now(), now(), 0, false)
                """,
                LISTING_ID, OWNER_USER_ID);
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
    void countingWrite_keepsThePublicReadInsideTheDocumentedBound() throws Exception {
        // Warmup: JIT, connection pools, PG buffers, the plan cache —
        // unique visitors, so the counting path is warm too.
        for (int i = 0; i < WARMUP; i++) {
            readAs("10.1." + (i / 250) + "." + (i % 250 + 1));
        }

        // Cohort A — the dedup-hit read (one Redis op, no DB write).
        // The visitor is one the WARMUP already marked (CodeRabbit round 1,
        // adopted): a fresh address would make the FIRST sample a dedup
        // miss — one locked insert inside the baseline cohort AND a total
        // of WARMUP+1+COHORT that breaks the counting assertion below.
        List<Long> dedupHit = new ArrayList<>(COHORT);
        for (int i = 0; i < COHORT; i++) {
            dedupHit.add(timedRead("10.1.0.1"));
        }

        // Cohort B — the full counting read (Redis + locked +1 + Envers +
        // commit), the hot-listing worst case: 120 unique visitors claim
        // the SAME (listing, today) bucket row
        List<Long> counting = new ArrayList<>(COHORT);
        for (int i = 0; i < COHORT; i++) {
            counting.add(timedRead("198.51." + (i / 250) + "." + (i % 250 + 1)));
        }

        long p50a = percentile(dedupHit, 0.50);
        long p95a = percentile(dedupHit, 0.95);
        long p99a = percentile(dedupHit, 0.99);
        long p50b = percentile(counting, 0.50);
        long p95b = percentile(counting, 0.95);
        long p99b = percentile(counting, 0.99);

        // The D-E7 evidence line — recorded in the CI logs and cited by
        // the PR's debt-closure note.
        System.out.printf(
                "L40 D-E7 measurement (ms) — public read p50/p95/p99:%n"
                        + "  dedup-hit read (no DB write):    %d / %d / %d%n"
                        + "  full counting read (locked +1):  %d / %d / %d%n"
                        + "  p95 marginal cost of counting:   %d ms (bound: %d ms)%n",
                p50a, p95a, p99a, p50b, p95b, p99b, p95b - p95a, P95_DIFF_BOUND_MS);

        // The counting itself actually happened (the measurement measured
        // the real path, not a no-op): the warmup alone wrote WARMUP rows
        // worth of +1s onto today's bucket.
        Long counted = jdbcTemplate.queryForObject(
                "SELECT view_count FROM listing_views_daily WHERE listing_id = ?",
                Long.class, LISTING_ID);
        assertThat(counted).isEqualTo((long) WARMUP + COHORT);

        assertThat(p95b - p95a)
                .as("p95 marginal cost of the counting write on the public read "
                        + "(D-E7): dedup-hit p95 = %d ms, counting p95 = %d ms", p95a, p95b)
                .isLessThan(P95_DIFF_BOUND_MS);
    }

    // ------------------------------------------------------------------
    // helpers

    private long timedRead(String remoteAddr) throws Exception {
        long start = System.nanoTime();
        mockMvc.perform(get("/api/v1/listings/{id}", LISTING_ID)
                        .with(request -> {
                            request.setRemoteAddr(remoteAddr);
                            return request;
                        }))
                .andExpect(status().isOk());
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void readAs(String remoteAddr) throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", LISTING_ID)
                        .with(request -> {
                            request.setRemoteAddr(remoteAddr);
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    /** Nearest-rank percentile over the measured latencies (ms). */
    private static long percentile(List<Long> samples, double q) {
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compare);
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.min(rank, sorted.size()) - 1);
    }

    private void clearDedupMarkers() {
        Set<String> keys = redisTemplate.keys(ListingViewCounter.DEDUP_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
