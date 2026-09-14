package com.marketplace.messaging;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.api.ResourceNotFoundException;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L34 (realestate systems plan §5 — lead capture) — the acceptance
 * criteria over the REAL chain: HTTP → security filter chain (the public
 * permitAll line) → controller → service → V52 schema → event → the
 * notifications module's real listener → the real preference machinery
 * (L22) → the LEAD_RECEIVED row and WS push.
 *
 * <p>Acceptance criteria covered here: (1) a guest submits against a live
 * listing ⇒ 201 + the provider's notification (in-app row + WS observed);
 * (2) an existing-but-not-live listing ⇒ 409, a missing one ⇒ 404; (4) the
 * provider's paged inbox + the READ move + a foreign provider's lead is a
 * 404; (5) the purge: the sender's lead texts die on base + mirror while
 * the row survives (the b-3 contract). Criterion (3) — the 429s — has its
 * own class ({@code LeadsRateLimitIntegrationTest}) with tiny instances,
 * the {@code RateLimitProblemDetailIntegrationTest} convention.
 *
 * <p>Schema honesty (the house convention): Flyway enabled +
 * {@code ddl-auto=none} against the postgis container — V52 is the schema
 * the flow runs on (V50/V51 require the extension, so the image is the
 * CI one). The liveness seam is the documented boundary
 * ({@code CatalogSpi} + {@code ListingPriceProvider}) — catalog's own
 * ACTIVE semantics are its tested contract; the users and provider rows
 * are seeded with raw SQL + ON CONFLICT DO NOTHING (the L22 convention)
 * because the real {@code ProviderLookupPort} and email channel resolve
 * from them. {@code SimpMessagingTemplate} is mocked to OBSERVE the push
 * (the L22 convention — no broker in the test profile). Each test stubs
 * only the seams it exercises (strict-stub clean).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
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

    /** The liveness seam at its documented boundary (catalog's own contract). */
    @MockitoBean
    CatalogSpi catalogSpi;

    @MockitoBean
    ListingPriceProvider listingPriceProvider;

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

    private UUID providerUserId;
    private UUID providerId;
    private UUID otherProviderUserId;
    private UUID otherProviderId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        providerUserId = UUID.randomUUID();
        providerId = UUID.randomUUID();
        otherProviderUserId = UUID.randomUUID();
        otherProviderId = UUID.randomUUID();
        listingId = UUID.randomUUID();

        seedUser(providerUserId, "l34-provider-" + providerUserId + "@example.com");
        seedUser(otherProviderUserId, "l34-other-" + otherProviderUserId + "@example.com");
        jdbc.update("""
                INSERT INTO provider_profiles (id, user_id, display_name, bio, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'VERIFIED', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, providerId, providerUserId, "L34 Host", "seed");
        jdbc.update("""
                INSERT INTO provider_profiles (id, user_id, display_name, bio, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'VERIFIED', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, otherProviderId, otherProviderUserId, "L34 Other", "seed");
    }

    private void seedUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, id, email, email, "PROVIDER");
    }

    private void liveListing() {
        when(catalogSpi.getActiveById(listingId)).thenReturn(new ProviderListingView(
                listingId, "L34 flat", "seed listing", "APARTMENT",
                100_00L, "SAR", providerId, "ACTIVE", 4, Instant.now(), Instant.now()));
    }

    private String leadBody() {
        return """
                {"contactName": "Sami Ahmad", "contactPhone": "+963991234567",
                 "message": "Is the flat still available for October?"}
                """;
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
        // line), the lead row, the event, the listener, the LEAD_RECEIVED
        // notification for the provider's user, and the WS push behind its
        // L22 preference (default on).
        liveListing();
        UUID leadId = submitLead("203.0.113.10");

        Integer leadCount = jdbc.queryForObject(
                "SELECT count(*) FROM listing_leads WHERE id = ? AND status = 'NEW'",
                Integer.class, leadId);
        assertThat(leadCount).isEqualTo(1);

        // The registry-driven listener ran: the in-app row landed for the
        // provider's USER (not the provider profile id) and the WS push
        // targeted the user's topic.
        awaitNotification(providerUserId);
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + providerUserId),
                any(com.marketplace.notifications.WebSocketNotification.class));
    }

    @Test
    void existingButNotLiveListingIs409AndMissingIs404() throws Exception {
        // Acceptance 2 — the disambiguation: the hot path resolves only
        // ACTIVE listings; the unfiltered projection separates the two.
        when(catalogSpi.getActiveById(listingId))
                .thenThrow(new ResourceNotFoundException("Listing", listingId));
        // getListingInfo still resolves => exists but not live => 409.
        when(listingPriceProvider.getListingInfo(listingId)).thenReturn(
                new ListingPriceProvider.ListingInfo(providerId, 100_00L, "SAR"));
        mockMvc.perform(post("/api/v1/listings/{id}/leads", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadBody())
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.11");
                            return req;
                        }))
                .andExpect(status().isConflict());

        // Both miss => the honest 404.
        when(listingPriceProvider.getListingInfo(listingId))
                .thenThrow(new ResourceNotFoundException("Listing", listingId));
        mockMvc.perform(post("/api/v1/listings/{id}/leads", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadBody())
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.12");
                            return req;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void providerInboxListsMarksReadAndHidesForeignLeads() throws Exception {
        // Acceptance 4 — the inbox: paged read, the one-way READ move, and
        // a foreign provider's lead is a 404 (the read is owner-scoped).
        liveListing();
        UUID leadId = submitLead("203.0.113.13");

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerUserId);

        mockMvc.perform(get("/api/v1/providers/me/leads")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(leadId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("NEW"));

        mockMvc.perform(patch("/api/v1/providers/me/leads/{leadId}", leadId)
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
        liveListing();
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
