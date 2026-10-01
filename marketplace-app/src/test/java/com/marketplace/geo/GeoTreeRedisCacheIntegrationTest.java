package com.marketplace.geo;

import test.config.IntegrationContainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.marketplace.provider.ProviderStatsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R10 regression guard for the geo-tree / provider-stats Redis cache value
 * seam — the live-measured defect family behind the frontend write-battery
 * cards BE-01, BE-03 and BE-05 (see
 * {@code docs/frontend-battery-33-defect-handoff-2026-09-27.md}, PR #412).
 *
 * <p><b>The measured defect chain (live + CI-diagnosed, 2026-09-27):</b>
 * production and staging answered {@code GET /api/v1/geo/tree} with a
 * deterministic 409 CONFLICT-001 whose canned detail ("Resource was
 * modified by another transaction") <em>looked</em> like optimistic locking.
 * The CI diagnostic on this branch pinned the real chain:
 * {@code @Cacheable("geo-tree")} cold PUT → spring-data-redis 4.1.1's
 * default {@code RedisValueSerializer} rejects the non-Serializable
 * {@code GeoNode} value with {@code IllegalStateException("Cannot serialize
 * value of type … without a serializer")} → the shared
 * {@code GlobalExceptionHandler} maps {@code IllegalStateException} to 409
 * CONFLICT-001 and the localized message bundle overlays the canned detail.
 * Every geo-linked listing detail dies the same way through
 * {@code addressChain → getTree()} (the battery's A/B: 409 with
 * {@code locationId}, 200 without) — and {@code provider-stats} carries the
 * identical defect through {@code ProviderStatsResponse} (STATS-409).
 *
 * <p><b>Why CI never saw it before:</b> the test profile forces
 * {@code spring.cache.type=simple} — the in-memory cache stores object
 * references and never serializes. This guard forces the production cache
 * type against a real Redis, exactly like the house's own
 * {@code ColdCacheRedisSerializationIntegrationTest} (which fixed the same
 * seam for {@code Page<ListingSummary>}, seven entities and
 * {@code PriceBreakdown} — but whose fix list never covered the two
 * DTO-record cache values this round closed:
 * {@code GeoLookupPort.GeoNode} and {@code ProviderStatsResponse}).
 *
 * <p><b>Guards:</b>
 * <ol>
 *   <li><b>geo-tree through the real HTTP surface</b> — cold miss → PUT →
 *       200, and the second read (the Redis HIT) also 200 with the seed
 *       chain intact (سوريا → ريف دمشق → قدسيا → neighborhoods).</li>
 *   <li><b>The value-level seam for the authenticated-only surface</b> —
 *       {@code ProviderStatsResponse} and the recursive {@code GeoNode}
 *       tree JDK-serialize and deserialize to equal values (the exact
 *       operation the Redis value serializer performs on every cold PUT
 *       and every HIT read).</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.cache.type=redis",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GeoTreeRedisCacheIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the house precedent (GeoModuleIntegrationTest).
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory (AdminModuleIntegrationTest pattern).
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Leg 1 — the public tree surface under the production cache type: the
     * cold miss must 200 (the PUT succeeds — the defect's exact failure
     * point), and the second read must 200 from the Redis HIT (the entry
     * really round-tripped through the serializer, not an in-memory
     * reference).
     */
    @Test
    void geoTree_underRedisCache_coldMissThenHit_both200() throws Exception {
        assertThat(cacheManager.getClass().getName())
                .as("the guard is only meaningful under the production cache type")
                .isEqualTo("org.springframework.data.redis.cache.RedisCacheManager");

        for (int call = 1; call <= 2; call++) {
            var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/geo/tree"))
                    .andReturn();
            int status = result.getResponse().getStatus();
            assertThat(status)
                    .as("geo/tree call #%d must be 200 — the 409 CONFLICT-001 family is fixed", call)
                    .isEqualTo(200);
            if (call == 1) {
                // The seed chain survived the composition (and the cache PUT):
                // سوريا (level 0) → ريف دمشق → قدسيا → the three neighborhoods.
                String body = result.getResponse().getContentAsString();
                assertThat(body).contains("\"slug\":\"syria\"");
                assertThat(body).contains("\"slug\":\"rif-dimashq\"");
                assertThat(body).contains("\"slug\":\"qudsayya\"");
            }
        }
    }

    /**
     * Leg 2 — the value-level seam for both fixed cache types: JDK
     * serialize → deserialize must round-trip equal values. This is the
     * exact operation {@code RedisValueSerializer} performs on every cold
     * PUT and every HIT read — the operation that threw
     * {@code IllegalStateException} before the records became
     * {@code Serializable}. {@code ProviderStatsResponse} is guarded here
     * (value level) because its HTTP surface is authenticated-only; the
     * geo tree is guarded end-to-end by leg 1.
     */
    @Test
    void cacheValueTypes_jdkRoundTrip_equalValues() throws Exception {
        // The recursive tree shape: root → city → two neighborhoods.
        com.marketplace.shared.api.GeoLookupPort.GeoNode leaf1 =
                new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), UUID.randomUUID(), 3,
                        "قدسيا البلد", "Qudsayya Old Town", "qudsayya-old-town", null);
        com.marketplace.shared.api.GeoLookupPort.GeoNode leaf2 =
                new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), UUID.randomUUID(), 3,
                        "ضاحية قدسيا", "Qudsayya Suburb", "qudsayya-suburb", List.of());
        com.marketplace.shared.api.GeoLookupPort.GeoNode city =
                new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), UUID.randomUUID(), 2,
                        "قدسيا", "Qudsayya", "qudsayya", List.of(leaf1, leaf2));
        com.marketplace.shared.api.GeoLookupPort.GeoNode root =
                new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), null, 0, "سوريا", "Syria", "syria", List.of(city));

        assertThat(roundTrip(root)).isEqualTo(root);
        assertThat(roundTrip(root).children().get(0).children()).hasSize(2);

        // R9: the cached stats value carries the per-currency net list —
        // the CurrencyAmount carrier is Serializable for exactly this seam.
        ProviderStatsResponse stats = new ProviderStatsResponse(
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T23:59:59Z"),
                0.75, List.of(new com.marketplace.shared.api.CurrencyAmount("SAR", 123_456L)), 9L);
        assertThat(roundTrip(stats)).isEqualTo(stats);
    }

    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(value);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            @SuppressWarnings("unchecked")
            T back = (T) ois.readObject();
            return back;
        }
    }
}
