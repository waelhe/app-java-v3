package com.marketplace.messaging;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L34 (realestate systems plan §5 — lead capture) — the acceptance
 * criteria over the REAL chain with the REAL catalog: HTTP → security
 * filter chain (the public permitAll line) → controller → service → the
 * real {@code CatalogService} liveness semantics → V52 schema → event →
 * the notifications module's real listener → the real preference
 * machinery (L22) → the LEAD_RECEIVED row and WS push.
 *
 * <p><b>Why the real catalog (the CI round-1 lesson):</b> mocking
 * {@code CatalogSpi} by interface in the full context replaced the
 * {@code catalogService} bean definition and broke the controller's
 * concrete-type injection (BeanNotOfRequiredTypeException). The honest
 * shape is stronger anyway: the liveness gate's semantics (ACTIVE vs
 * PAUSED vs missing) are catalog's own tested contract, exercised here
 * through real SQL-seeded listings.
 *
 * <p><b>The id-space fact (A1/V2):</b> {@code provider_listings.provider_id}
 * references {@code users(id)} — the listing's provider IS a user, so the
 * lead's provider column and the inbox key are the user id directly (the
 * same seam {@code onBookingCreated} uses). The users are seeded with
 * raw SQL + ON CONFLICT DO NOTHING (the L22 convention);
 * {@code SimpMessagingTemplate} is mocked to OBSERVE the push.
 *
 * <p>Acceptance criteria: (1) a guest submits against a live listing ⇒
 * 201 + the provider's notification (in-app row + WS observed); (2) an
 * existing-but-not-live listing ⇒ 409, a missing one ⇒ 404 — through the
 * REAL liveness semantics; (4) the provider's paged inbox + the READ move
 * + a foreign provider's lead is a 404; (5) the purge: the sender's lead
 * texts die on base + mirror while the row survives (the b-3 contract).
 * Criterion (3) — the 429s — has its own class with tiny instances.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // One lead per sender fingerprint: every test here submits from
        // its own address exactly once — and the concurrent-lock test
        // NEEDS a cap of 1 to prove the advisory serialization.
        "marketplace.messaging.leads.daily-cap-per-sender=1",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LeadsIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches NotificationPreferencesIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    /** Delivery observation only — the gating logic under test is production code. */
    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeadsService leadsService;

    @Autowired
    private com.marketplace.messaging.spi.MessagingContentPurgeAdapter purgeAdapter;

    @Autowired
    private LeadsFingerprintCleanupJob fingerprintCleanupJob;

    private UUID providerUserId;
    private UUID otherProviderUserId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        providerUserId = UUID.randomUUID();
        otherProviderUserId = UUID.randomUUID();
        listingId = UUID.randomUUID();

        seedUser(providerUserId, "l34-provider-" + providerUserId + "@example.com");
        seedUser(otherProviderUserId, "l34-other-" + otherProviderUserId + "@example.com");
        // The A1/V2 fact: the listing's provider_id IS the user id.
        seedListing(listingId, providerUserId, "ACTIVE");
    }

    private void seedUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, id, email, email, "L34 user");
    }

    private void seedListing(UUID id, UUID providerUserId, String status) {
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'L34 flat', 'seed listing', 'APARTMENT', 10000, 'SAR', ?)
                ON CONFLICT (id) DO NOTHING
                """, id, providerUserId, status);
    }

    private String leadBody() {
        return """
                {"contactName": "Sami Ahmad", "contactPhone": "+963991234567",
                 "message": "Is the flat still available for October?"}
                """;
    }

    private LeadRequest request() {
        return new LeadRequest("Sami Ahmad", "+963991234567", "Is the flat still available for October?");
    }

    private UUID submitLead(String remoteAddr) throws Exception {
        String response = mockMvc.perform(post("/api/v1/listings/{id}/leads", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadBody())
                        .with(req -> {
                            req.setRemoteAddr(remoteAddr);
                            return req;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readValue(response, Map.class).get("id").toString());
    }

    @Test
    void guestLeadOnLiveListingCreatesAndAlertsTheProvider() throws Exception {
        // Acceptance 1 — the full chain: anonymous POST (the permitAll
        // line), the lead row, the real event, the listener, the
        // LEAD_RECEIVED notification for the provider's user, and the WS
        // push behind its L22 preference (default on).
        UUID leadId = submitLead("203.0.113.10");

        Integer leadCount = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads WHERE id = ? AND status = 'NEW' AND provider_id = ?",
                Integer.class, leadId, providerUserId);
        assertThat(leadCount).isEqualTo(1);

        awaitNotification(providerUserId);
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + providerUserId),
                any(com.marketplace.notifications.WebSocketNotification.class));
    }

    @Test
    void existingButNotLiveListingIs409AndMissingIs404() throws Exception {
        // Acceptance 2 — through the REAL liveness semantics: a PAUSED
        // listing resolves in the unfiltered projection (409 — exists but
        // not live); a missing id misses both (the honest 404). A FRESH
        // id for the paused row — the seed's ON CONFLICT DO NOTHING keeps
        // the original ACTIVE (the CI round-2 lesson).
        UUID pausedListingId = UUID.randomUUID();
        seedListing(pausedListingId, providerUserId, "PAUSED");
        mockMvc.perform(post("/api/v1/listings/{id}/leads", pausedListingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadBody())
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.11");
                            return req;
                        }))
                .andExpect(status().isConflict());

        UUID missing = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/listings/{id}/leads", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadBody())
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.12");
                            return req;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    void providerInboxListsMarksReadAndHidesForeignLeads() throws Exception {
        // Acceptance 4 — the inbox: paged read, the one-way READ move, and
        // a foreign provider's lead is a 404 (the read is owner-scoped by
        // the user id — the A1/V2 key).
        UUID leadId = submitLead("203.0.113.13");

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerUserId);

        // The resource-server chain authenticates BEARER tokens (the
        // measured house pattern — a TestingAuthenticationToken answers
        // 401 on this chain): the mocked JWT rides the real filter chain.
        mockMvc.perform(get("/api/v1/providers/me/leads")
                        .with(jwt())
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(leadId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("NEW"));

        mockMvc.perform(patch("/api/v1/providers/me/leads/{leadId}", leadId)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READ"));

        Integer readCount = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads WHERE id = ? AND status = 'READ'",
                Integer.class, leadId);
        assertThat(readCount).isEqualTo(1);

        // The foreign provider: the same lead is not in his inbox — 404,
        // and the row is untouched.
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(otherProviderUserId);
        mockMvc.perform(patch("/api/v1/providers/me/leads/{leadId}", leadId)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ARCHIVED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void purgeKillsTheAuthenticatedSendersLeadTextsButKeepsTheRow() {
        // Acceptance 5 — the b-3 contract through the messaging module's
        // real adapter: the sender's contact texts die on the base table
        // AND the Envers mirror, the row itself survives with its
        // structure, and a re-run matches nothing (idempotence).
        UUID senderId = UUID.randomUUID();
        seedUser(senderId, "l34-sender-" + senderId + "@example.com");

        // Drive the write through the real service so Envers mirrors it
        // and the attribution is captured (the optional-identity seam).
        when(currentUserProvider.tryGetCurrentUserId(any())).thenReturn(Optional.of(senderId));
        LeadResponse created = leadsService.createLead(listingId,
                new LeadRequest("Sami Ahmad", "+963991234567", "Call me about October"),
                null, "203.0.113.14");

        Integer attributed = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads WHERE id = ? AND sender_user_id = ?",
                Integer.class, created.id(), senderId);
        assertThat(attributed).isEqualTo(1);

        int purged = purgeAdapter.purgeAuthoredTexts(senderId);
        assertThat(purged).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT contact_name, contact_phone, message FROM listing_leads WHERE id = ?",
                created.id());
        assertThat(row.get("contact_name")).isEqualTo("[purged]");
        assertThat(row.get("contact_phone")).isEqualTo("[purged]");
        assertThat(row.get("message")).isEqualTo("[purged]");

        // The Envers mirror died with it (the V24 convention).
        Integer mirrorPurged = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads_aud WHERE id = ? AND message = ?",
                Integer.class, created.id(), "[purged]");
        assertThat(mirrorPurged).isGreaterThanOrEqualTo(1);

        // Idempotence — the port contract: a re-run matches nothing.
        assertThat(purgeAdapter.purgeAuthoredTexts(senderId)).isEqualTo(0);
    }

    @Test
    void concurrentSubmissionsFromOneFingerprintSerializeOnTheAdvisoryLock() throws Exception {
        // The CodeRabbit round-1 adoption proof: two threads, one
        // fingerprint, cap 1 — the advisory transaction lock makes the
        // second submission WAIT for the first to commit, count the
        // committed row, and reject. Exactly one lead, exactly one
        // TooManyRequestsException — deterministic WITH the lock, and a
        // regression detector without it (both would land).
        java.util.concurrent.ConcurrentLinkedQueue<Object> results = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Runnable submit = () -> {
            try {
                results.add(leadsService.createLead(listingId, request(), null, "203.0.113.20"));
            } catch (RuntimeException e) {
                results.add(e);
            }
        };
        Thread first = new Thread(submit);
        Thread second = new Thread(submit);
        first.start();
        second.start();
        first.join();
        second.join();

        long landed = results.stream().filter(r -> r instanceof LeadResponse).count();
        long rejected = results.stream().filter(r -> r instanceof com.marketplace.shared.api.TooManyRequestsException).count();
        assertThat(landed).as("exactly one lead wins the window").isEqualTo(1);
        assertThat(rejected).as("exactly one submission is capped").isEqualTo(1);
    }

    @Test
    void fingerprintSweepNullsOnlyClosedWindows() {
        // The retention adoption: the fingerprint lives for its 24h
        // window (+1h margin) and no longer — base and mirror alike. A
        // fresh lead keeps it; a backdated one loses it everywhere.
        LeadResponse fresh = leadsService.createLead(listingId, request(), null, "203.0.113.30");
        LeadResponse closed = leadsService.createLead(listingId, request(), null, "203.0.113.31");
        jdbc.update("UPDATE listing_leads SET created_at = now() - interval '26 hours' WHERE id = ?",
                closed.id());
        jdbc.update("UPDATE listing_leads_aud SET created_at = now() - interval '26 hours' WHERE id = ?",
                closed.id());

        fingerprintCleanupJob.expireClosedWindowFingerprints();

        Integer freshKept = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads WHERE id = ? AND sender_ip_hash IS NOT NULL",
                Integer.class, fresh.id());
        Integer closedCleared = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads WHERE id = ? AND sender_ip_hash IS NOT NULL",
                Integer.class, closed.id());
        Integer closedMirrorCleared = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads_aud WHERE id = ? AND sender_ip_hash IS NOT NULL",
                Integer.class, closed.id());
        assertThat(freshKept).as("the live window keeps its fingerprint").isEqualTo(1);
        assertThat(closedCleared).as("the closed window loses it (base)").isEqualTo(0);
        assertThat(closedMirrorCleared).as("the closed window loses it (mirror)").isEqualTo(0);
    }

    @Test
    void inboxRequiresAuthentication() throws Exception {
        // The public surface is the POST only — the inbox is the
        // provider's authenticated surface (401 through the chain).
        mockMvc.perform(get("/api/v1/providers/me/leads"))
                .andExpect(status().isUnauthorized());
    }

    private void awaitNotification(UUID userId) throws InterruptedException {
        // The listener runs after the lead transaction commits, in its own
        // transaction — poll briefly for the row rather than sleeping a
        // fixed time (bounded, deterministic in practice).
        for (int i = 0; i < 50; i++) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'LEAD_RECEIVED'",
                    Integer.class, userId);
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("LEAD_RECEIVED notification never landed for " + userId);
    }
}
