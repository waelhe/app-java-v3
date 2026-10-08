package com.marketplace.releases;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-18 (compliance plan C.12) — the closing gate: «IT رحلة: نشر إصدار من
 * اللوحة ← المسار العام ← حدث الإشعار». One journey, the real Flyway
 * schema (V117), the real resource-server chain, both REST surfaces:
 * <ol>
 *   <li><b>The console's publication</b> — an ADMIN-role JWT publishes a
 *       release through {@code POST /api/v1/admin/releases} (the manager's
 *       surface, the three-layer gate riding the real chain).</li>
 *   <li><b>The public boot path</b> — an ANONYMOUS caller reads the same
 *       release back off {@code GET /api/v1/releases/latest?channel=...}
 *       (the §7/2 first-screen moment, permitAll by contract: the client
 *       boots before any session exists).</li>
 *   <li><b>The notification fan-out</b> — {@code PlatformReleasePublishedEvent}
 *       reaches the registry-tracked listener contract: the plan records the
 *       notifications consumer as Track B's registered late arrival
 *       («المستهلك بيد B عبر السجل — الواصل المتأخر»), so this journey
 *       carries a probe listener in the SAME shape the A-13 gate used —
 *       when the real consumer lands it replaces the probe and the
 *       contract is already standing: the event is delivered, its registry
 *       publication completes into the archive.</li>
 * </ol>
 *
 * <p><b>The contract's negative pins:</b> an unknown channel answers the
 * house 400 with the vocabulary listed (the boundary-parse convention);
 * a malformed version answers the boundary's own 400; an authenticated
 * non-admin cannot publish; re-publishing the same (channel, version)
 * answers the V70-stance 409 (release identities are never recycled); a
 * channel with nothing published answers the honest 404, never a
 * fabricated empty contract.</p>
 *
 * <p>Boot pattern follows {@code CategoryRegistryIntegrationTest}: the
 * isolated postgis container via {@code @ServiceConnection}, Flyway
 * enabled, {@code ddl-auto=none}, {@code JdbcTemplate} cleanup — zero
 * mocked seams (the admin role rides the real chain as a JWT authorities
 * grant).</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class PlatformReleaseJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReleaseProbeListener probeListener;

    /**
     * The release identity this class publishes — unique per run (a fresh
     * minor-ish suffix), so the class owns its rows across the journey and
     * the identity-recycling pin, with {@code @AfterEach} cleanup.
     */
    private static final String VERSION = "3.14." + (1000 + (System.currentTimeMillis() % 9000));

    /**
     * The notifications-consumer stand-in — the A-13 RestartProbeListener
     * shape: an {@code @ApplicationModuleListener} (registry-tracked, async,
     * REQUIRES_NEW — the exact dispatch shape the production notifications
     * listeners ride), so the completion advisor and archive machinery run
     * the production path. The REAL consumer is Track B's late arrival; its
     * contract is what this probe measures.
     */
    @TestConfiguration
    static class ProbeConfig {

        @Bean
        public ReleaseProbeListener releaseProbeListener() {
            return new ReleaseProbeListener();
        }
    }

    public static class ReleaseProbeListener {

        private final AtomicReference<String> receipt = new AtomicReference<>();

        /** Control seam — a method, not field access: the bean is AOP-proxied. */
        public String receipt() {
            return receipt.get();
        }

        @ApplicationModuleListener
        public void onPlatformReleasePublished(
                com.marketplace.shared.api.PlatformReleasePublishedEvent event) {
            receipt.set(event.channel() + "@" + event.version());
        }
    }

    @AfterEach
    void cleanProbeRows() {
        jdbc.update("DELETE FROM event_publication WHERE serialized_event LIKE '%PlatformReleasePublishedEvent%'");
        jdbc.update("DELETE FROM event_publication_archive WHERE serialized_event LIKE '%PlatformReleasePublishedEvent%'");
        jdbc.update("DELETE FROM platform_releases WHERE release_version = ?", VERSION);
        jdbc.update("DELETE FROM platform_releases_aud WHERE release_version = ?", VERSION);
    }

    @Test
    void theReleaseJourneyConsoleToPublicPathToNotificationEvent() throws Exception {
        // Phase 1 — the console's publication (the manager's surface).
        mockMvc.perform(post("/api/v1/admin/releases")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel": "ANDROID", "version": "%s",
                                 "changelog": "The neighborhood market arrives on mobile.",
                                 "minVersion": "3.0.0", "mandatory": true, "graceHours": 72}
                                """.formatted(VERSION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("ANDROID"))
                .andExpect(jsonPath("$.version").value(VERSION))
                .andExpect(jsonPath("$.minVersion").value("3.0.0"))
                .andExpect(jsonPath("$.mandatory").value(true))
                .andExpect(jsonPath("$.graceHours").value(72));

        // Phase 2 — the public boot path: the anonymous §7/2 read answers
        // the SAME facts the console published.
        mockMvc.perform(get("/api/v1/releases/latest").param("channel", "ANDROID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("ANDROID"))
                .andExpect(jsonPath("$.version").value(VERSION))
                .andExpect(jsonPath("$.changelog").value("The neighborhood market arrives on mobile."))
                .andExpect(jsonPath("$.minVersion").value("3.0.0"))
                .andExpect(jsonPath("$.mandatory").value(true))
                .andExpect(jsonPath("$.graceHours").value(72));

        // Phase 3 — the notification fan-out: the registry-tracked listener
        // contract receives the event (async — poll the receipt), and the
        // publication completes into the archive (the A-13 machinery
        // guarding Track B's late-arriving consumer).
        poll("the release publication event reached the registry-tracked listener contract",
                () -> ("ANDROID@" + VERSION).equals(probeListener.receipt()));
        assertThat(probeListener.receipt())
                .as("the release publication event reached the registry-tracked listener contract")
                .isEqualTo("ANDROID@" + VERSION);
        poll("the event's publication completed into the archive (the registry guard)",
                () -> archivedFor(VERSION) >= 1);
        assertThat(archivedFor(VERSION))
                .as("the event's publication completed into the archive (the registry guard)")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void theContractsNegativePins() throws Exception {
        // An unknown channel answers the house 400 with the vocabulary
        // listed — the boundary-parse convention, before any service call.
        mockMvc.perform(get("/api/v1/releases/latest").param("channel", "TVOS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.stringContainsInOrder("Unknown release channel", "ANDROID")));

        // A channel with nothing published answers the honest 404.
        mockMvc.perform(get("/api/v1/releases/latest").param("channel", "WEB"))
                .andExpect(status().isNotFound());

        // A malformed version answers the boundary's own 400 (the V117
        // CHECK twins pinned by bean validation).
        mockMvc.perform(post("/api/v1/admin/releases")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel": "IOS", "version": "not-semver",
                                 "changelog": "x", "minVersion": "1.0.0",
                                 "mandatory": false, "graceHours": 24}
                                """))
                .andExpect(status().isBadRequest());

        // The role gate: an authenticated non-admin cannot publish.
        mockMvc.perform(post("/api/v1/admin/releases")
                        .with(jwt().authorities(() -> "ROLE_CONSUMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel": "IOS", "version": "1.2.3",
                                 "changelog": "x", "minVersion": "1.0.0",
                                 "mandatory": false, "graceHours": 24}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void releaseIdentitiesAreNeverRecycled() throws Exception {
        // Publish once...
        mockMvc.perform(post("/api/v1/admin/releases")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel": "IOS", "version": "%s",
                                 "changelog": "First publication.",
                                 "minVersion": "1.0.0", "mandatory": false, "graceHours": 24}
                                """.formatted(VERSION)))
                .andExpect(status().isCreated());

        // ...and the same (channel, version) pair answers the V70-stance 409.
        mockMvc.perform(post("/api/v1/admin/releases")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel": "IOS", "version": "%s",
                                 "changelog": "Second attempt at the same identity.",
                                 "minVersion": "1.0.0", "mandatory": false, "graceHours": 24}
                                """.formatted(VERSION)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.stringContainsInOrder("never recycled")));
    }

    // ---------- helpers (the house patterns) ----------

    private long archivedFor(String version) {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication_archive"
                        + " where serialized_event like ? and completion_date is not null",
                Long.class, "%" + version + "%");
        return count == null ? 0 : count;
    }

    /**
     * Polls (up to 30s, 200ms interval — the house pattern; no Awaitility
     * in this reactor) until the condition holds, failing with a diagnostic
     * message otherwise. Listener dispatch and registry completion are
     * async, so state transitions must be polled.
     */
    private void poll(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }
}
