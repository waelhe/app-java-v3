package com.marketplace.geo;

import java.util.List;

import com.marketplace.shared.api.GeoLookupPort;
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
import org.testcontainers.utility.DockerImageName;

/**
 * R10 diagnostic (frontend handoff BE-01/BE-03): the production AND staging
 * environments answer {@code GET /api/v1/geo/tree} with a deterministic 409
 * whose detail is the {@code handleOptimisticLock} canned message ("Resource
 * was modified by another transaction") — while the seed rows are all intact
 * (measured live 2026-09-27: the root سوريا is visible through suggest and
 * children at every level; the frontend handoff's "rows without root"
 * root-cause attribution is refuted by those measurements).
 *
 * <p>Measured facts so far (R10):
 * <ul>
 *   <li>The only structural difference between the failing read
 *       ({@code getTree()}) and the working ones (children/suggest) is
 *       {@code @Cacheable("geo-tree")}.</li>
 *   <li>{@code GeoNode} — the cached value — does NOT implement
 *       {@code Serializable}: JDK serialization of it fails with
 *       {@code NotSerializableException} (measured directly).</li>
 *   <li>The house's own {@code ColdCacheRedisSerializationIntegrationTest}
 *       javadoc documents the identical defect family for other cached types
 *       (fixed there: Page, seven entities, PriceBreakdown) — but its fix
 *       list does NOT include {@code GeoNode} (geo-tree) or
 *       {@code ProviderStatsResponse} (provider-stats).</li>
 *   <li>The documented failure mode of that family was HTTP 500 INT-001;
 *       the live /geo/tree verdict is 409 CONFLICT-001 with the optimistic
 *       lock canned detail — this test pins down the exact exception and
 *       the translation path on a real Redis, which no statically-readable
 *       code path explains.</li>
 * </ul>
 *
 * <p>This test asserts only that the request completes; the printed evidence
 * (exception class + stack + HTTP verdict) is the deliverable for the root
 * fix. It follows the exact precedent of
 * {@code ColdCacheRedisSerializationIntegrationTest} (same containers, same
 * profile override) — the house's own instrument for this seam.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.cache.type=redis",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GeoTreeRedisCacheReproductionTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the house precedent (GeoModuleIntegrationTest).
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory (AdminModuleIntegrationTest pattern).
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private GeoService geoService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    void geoTree_underRedisCache_measuresTheRealVerdict() {
        System.out.println("=== R10 DIAGNOSTIC: cache manager = "
                + cacheManager.getClass().getName() + " ===");

        // ---- Leg 1: the direct service call (below MVC) ----
        System.out.println("=== leg 1: geoService.getTree() direct ===");
        try {
            Object tree = geoService.getTree();
            System.out.println("DIRECT CALL SUCCEEDED: root slug = "
                    + ((GeoLookupPort.GeoNode) tree).slug());
        } catch (Throwable t) {
            System.out.println("DIRECT CALL THREW: " + t.getClass().getName()
                    + ": " + t.getMessage());
            Throwable cur = t;
            while (cur.getCause() != null && cur.getCause() != cur) {
                cur = cur.getCause();
                System.out.println("  caused by: " + cur.getClass().getName()
                        + ": " + cur.getMessage());
            }
            StackTraceElement[] st = t.getStackTrace();
            System.out.println("  top frames:");
            for (int i = 0; i < Math.min(12, st.length); i++) {
                System.out.println("    at " + st[i]);
            }
        }

        // ---- Leg 2: the HTTP surface ----
        for (int call = 1; call <= 2; call++) {
            System.out.println("=== leg 2." + call + ": GET /api/v1/geo/tree via MockMvc ===");
            try {
                var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/geo/tree"))
                        .andReturn();
                System.out.println("STATUS: " + result.getResponse().getStatus());
                String body = result.getResponse().getContentAsString();
                System.out.println("BODY: " + (body.length() > 900 ? body.substring(0, 900) + "…" : body));
                Exception ex = result.getResolvedException();
                if (ex != null) {
                    System.out.println("RESOLVED EXCEPTION: " + ex.getClass().getName() + ": " + ex.getMessage());
                    StackTraceElement[] st = ex.getStackTrace();
                    for (int i = 0; i < Math.min(8, st.length); i++) {
                        System.out.println("    at " + st[i]);
                    }
                }
            } catch (Exception e) {
                System.out.println("REQUEST BLEW UP OUTSIDE MVC: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }

        // ---- Leg 3 (control): a working @Cacheable surface with a
        // Serializable cached value — proves the Redis cache itself works
        // and the failure is specific to the geo-tree value type. ----
        System.out.println("=== leg 3 (control): GET /api/v1/listings via MockMvc ===");
        try {
            var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/listings"))
                    .andReturn();
            System.out.println("STATUS: " + result.getResponse().getStatus());
        } catch (Exception e) {
            System.out.println("CONTROL BLEW UP: " + e.getClass().getName() + ": " + e.getMessage());
        }
        // Deliberately empty assertion: measurement harness, not a verdict.
    }
}
