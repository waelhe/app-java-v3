package com.marketplace.identity;

import test.config.IntegrationContainers;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4 (yelp-level plan §5 — G21, provider follows) — the acceptance criteria
 * over the REAL chain: HTTP → the resource-server chain → the /me follow
 * surface (the SavedSearch house shape) → V93's partial-unique pair → the
 * REAL catalog activation ({@code CatalogService.activate} publishes
 * {@code ListingActivatedEvent} inside its transaction) → the identity
 * module's follow bridge (its own transaction) → the alert ledger (native
 * ON CONFLICT on the unique index) → one {@code FollowedProviderNewListingEvent}
 * per NEW (follower, listing) pair → the notifications module's real
 * listener → the {@code FOLLOWED_PROVIDER_NEW_LISTING} row (V93's widened
 * CHECK, V94's VALIDATE) and the WS push behind the L22 preference.
 *
 * <p>Seeding follows the L35/L22 conventions: raw SQL + ON CONFLICT DO
 * NOTHING; {@code CurrentUserProvider} is mocked to STITCH the identity
 * (the jwt() post-processor rides the real filter chain) and
 * {@code SimpMessagingTemplate} is mocked to OBSERVE the push.
 *
 * <p>Acceptance criteria covered (the plan's own W4 row): the follow
 * triggers "تنبيهًا واحدًا محترمًا للتفضيل" — exactly ONE notification per
 * follower per listing announcement, proven twice: (1) a registry
 * re-delivery of the same activation inserts nothing and notifies nobody
 * again; (2) unfollow/re-follow churn on the SAME listing never re-opens
 * the already-delivered alert (the ledger's key is the (follower, listing)
 * pair, not the follow row). Plus the /me surface's own gates: anonymous
 * 401, unknown provider 404, self-follow 400, live duplicate 409, the
 * owner-scoped unfollow 404, and the list's composed provider identity.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderFollowIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches SavedSearchIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

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
    private ProviderFollowService providerFollowService;

    private UUID consumerUserId;
    private UUID providerUserId;
    private UUID providerProfileId;

    @BeforeEach
    void seed() {
        // The pristine follow world (the SavedSearchIntegrationTest root
        // fix): one shared Spring context and one container across this
        // class's tests, and the bridge scans EVERY live follow of the
        // activation's provider regardless of which test created it. Raw
        // SQL writes no Envers revisions, so the deletes are order-free
        // and audit-silent.
        jdbc.update("DELETE FROM provider_follow_alerts");
        jdbc.update("DELETE FROM provider_follows");
        jdbc.update("DELETE FROM notifications WHERE type = 'FOLLOWED_PROVIDER_NEW_LISTING'");

        consumerUserId = UUID.randomUUID();
        providerUserId = UUID.randomUUID();
        providerProfileId = UUID.randomUUID();

        seedUser(consumerUserId, "w4-consumer-" + consumerUserId + "@example.com");
        seedUser(providerUserId, "w4-provider-" + providerUserId + "@example.com");
        seedProviderProfile(providerProfileId, providerUserId);
    }

    private void seedUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, id, email, email, "W4 user");
    }

    private void seedProviderProfile(UUID id, UUID userId) {
        jdbc.update("""
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at)
                VALUES (?, ?, 'w4-test', 'PENDING', ?, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, "W4 followed provider", userId);
    }

    /** A DRAFT listing of the followed provider — the announcement candidate. */
    private UUID seedDraftListing() {
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'W4 listing', 'w4 follow test', 'APARTMENT', 10000, 'SAR', 'DRAFT')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerUserId);
        return listingId;
    }

    /** The follow write through the REAL chain (201 + the composed view). */
    private UUID follow() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        String response = mockMvc.perform(post("/api/v1/me/follows")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\": \"" + providerProfileId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(providerProfileId.toString()))
                .andExpect(jsonPath("$.providerDisplayName").value("W4 followed provider"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response).get("id").toString().replace("\"", ""));
    }

    private void activateListing(UUID listingId) throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerUserId);
        // The role-carrying jwt (the SavedSearchIntegrationTest pattern):
        // activate() is @PreAuthorize("hasRole('PROVIDER')") and the bare
        // jwt() token carries no authorities — a plain 403.
        mockMvc.perform(post("/api/v1/listings/{id}/activate", listingId)
                        .with(jwt().jwt(j -> j.subject("w4-provider"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_PROVIDER"))))
                .andExpect(status().isOk());
    }

    /**
     * Deterministic async wait (the SavedSearchIntegrationTest contract):
     * a publication row is written atomically with the publishing commit
     * and disappears when the LAST listener completes — the test profile
     * DELETES completed publications. The follow wave has TWO chained
     * publications: the catalog's {@code ListingActivatedEvent} (consumed
     * by the identity bridge) and the bridge's own
     * {@code FollowedProviderNewListingEvent} (consumed by the
     * notifications listener) — each awaited by its own pattern, in order.
     */
    private void awaitPublicationsCompleted(String eventTypePattern) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Integer pending = jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE event_type LIKE ?",
                    Integer.class, eventTypePattern);
            if (pending == null || pending == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the listeners for " + eventTypePattern + " never completed");
    }

    private void awaitFollowWaveCompleted() throws InterruptedException {
        awaitPublicationsCompleted("%ListingActivatedEvent%");
        awaitPublicationsCompleted("%FollowedProviderNewListingEvent%");
    }

    private void awaitNotification(int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'FOLLOWED_PROVIDER_NEW_LISTING'",
                    Integer.class, consumerUserId);
            if (n != null && n >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("FOLLOWED_PROVIDER_NEW_LISTING notification never landed for " + consumerUserId);
    }

    @Test
    void aFollowedProvidersActivationAlertsTheFollowerExactlyOnce() throws Exception {
        // The plan's acceptance: "المتابعة تطلق تنبيهًا واحدًا محترمًا
        // للتفضيل" — the full chain, ONE notification, the in-app row +
        // the WS push behind the default-on L22 preference.
        UUID listingId = seedDraftListing();
        follow();

        activateListing(listingId);

        awaitNotification(1);
        awaitFollowWaveCompleted();
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'FOLLOWED_PROVIDER_NEW_LISTING'",
                Integer.class, consumerUserId);
        assertThat(notifications).isEqualTo(1);
        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE recipient_id = ? AND type = 'FOLLOWED_PROVIDER_NEW_LISTING'",
                String.class, consumerUserId);
        assertThat(message).contains(listingId.toString());
        // the WS push behind the default-on preference — observed once
        verify(messagingTemplate, timeout(5000).times(1)).convertAndSend(
                eq("/topic/notifications/" + consumerUserId),
                any(com.marketplace.notifications.WebSocketNotification.class));
        // the ledger row — the (follower, listing) pair EVER alerted
        Integer ledger = jdbc.queryForObject(
                "SELECT count(*) FROM provider_follow_alerts WHERE user_id = ? AND listing_id = ?",
                Integer.class, consumerUserId, listingId);
        assertThat(ledger).isEqualTo(1);
    }

    @Test
    void aRedeliveredActivationIsTheQuietNoOp() throws Exception {
        // The at-least-once D-E10 semantics: the registry may re-deliver
        // the same activation (the listener body re-run — the same seam
        // SavedSearchIntegrationTest re-invokes). The ledger's ON CONFLICT
        // skip answers 0, nothing is published, nobody is told twice.
        UUID listingId = seedDraftListing();
        follow();
        activateListing(listingId);
        awaitNotification(1);
        awaitFollowWaveCompleted();

        int alerted = providerFollowService.onListingActivated(listingId, providerUserId);

        assertThat(alerted).isZero();
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'FOLLOWED_PROVIDER_NEW_LISTING'",
                Integer.class, consumerUserId);
        assertThat(notifications).isEqualTo(1);
        Integer ledger = jdbc.queryForObject(
                "SELECT count(*) FROM provider_follow_alerts WHERE listing_id = ?",
                Integer.class, listingId);
        assertThat(ledger).isEqualTo(1);
    }

    @Test
    void unfollowAndRefollowChurnNeverReopensTheDeliveredAlert() throws Exception {
        // The ledger's key is the (follower, listing) pair EVER alerted —
        // NOT the follow row: the plan's one-alert guarantee survives the
        // follow lifecycle itself. The same listing re-announced after a
        // re-follow stays silent for the pair; a NEW listing alerts again.
        UUID firstListing = seedDraftListing();
        UUID followId = follow();
        activateListing(firstListing);
        awaitNotification(1);
        awaitFollowWaveCompleted();

        // the unfollow: owner-scoped soft delete, the row survives withdrawn
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        mockMvc.perform(delete("/api/v1/me/follows/{id}", followId).with(jwt()))
                .andExpect(status().isNoContent());
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM provider_follows WHERE id = ? AND is_deleted = FALSE",
                Integer.class, followId);
        Integer archived = jdbc.queryForObject(
                "SELECT count(*) FROM provider_follows WHERE id = ? AND is_deleted = TRUE",
                Integer.class, followId);
        assertThat(live).isZero();
        assertThat(archived).isEqualTo(1);

        // the re-follow: the pair was freed, so the write is legal again
        follow();

        // the SAME listing's pair was already alerted — the bridge skips
        int reAlerted = providerFollowService.onListingActivated(firstListing, providerUserId);
        assertThat(reAlerted).isZero();

        // a NEW listing of the same provider alerts the re-follower again
        UUID secondListing = seedDraftListing();
        activateListing(secondListing);
        awaitNotification(2);
        awaitFollowWaveCompleted();
        Integer ledger = jdbc.queryForObject(
                "SELECT count(*) FROM provider_follow_alerts WHERE user_id = ?",
                Integer.class, consumerUserId);
        assertThat(ledger).isEqualTo(2);
    }

    @Test
    void theMeSurfaceGatesTheWrites() throws Exception {
        // Anonymous → 401 through the real resource-server chain (the /me
        // family's standing contract — no security-config change).
        mockMvc.perform(post("/api/v1/me/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\": \"" + providerProfileId + "\"}"))
                .andExpect(status().isUnauthorized());

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);

        // an unknown provider is the honest 404 — never a silently-empty page
        mockMvc.perform(post("/api/v1/me/follows")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        // a member never follows his own provider profile — 400
        UUID ownProfile = UUID.randomUUID();
        seedProviderProfile(ownProfile, consumerUserId);
        mockMvc.perform(post("/api/v1/me/follows")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\": \"" + ownProfile + "\"}"))
                .andExpect(status().isBadRequest());

        // a live duplicate answers the explicit 409
        follow();
        mockMvc.perform(post("/api/v1/me/follows")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\": \"" + providerProfileId + "\"}"))
                .andExpect(status().isConflict());

        // a foreign id is an honest 404 on the withdraw
        mockMvc.perform(delete("/api/v1/me/follows/{id}", UUID.randomUUID()).with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListComposesTheFollowedProvidersCurrentIdentity() throws Exception {
        follow();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        mockMvc.perform(get("/api/v1/me/follows").with(jwt())
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].providerId").value(providerProfileId.toString()))
                .andExpect(jsonPath("$.content[0].providerDisplayName").value("W4 followed provider"));
    }

    @Test
    void theAlertHonorsTheStandingPerTypeChannelPreference() throws Exception {
        // The plan's W4 acceptance — "المتابعة تطلق تنبيهًا واحدًا محترمًا
        // للتفضيل": the L22 matrix gates the push channels per
        // (follower, type, channel) while the in-app row always lands
        // ("داخل التطبيق دائمًا"). The PUT itself also proves V93's widened
        // CHECK accepts the ninth type on the real Flyway schema — the
        // row write is the DB-side half of the type addition's contract.
        UUID listingId = seedDraftListing();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\": [{\"type\": \"FOLLOWED_PROVIDER_NEW_LISTING\", "
                                + "\"channel\": \"WS\", \"enabled\": false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(30));

        follow();
        activateListing(listingId);
        awaitNotification(1);
        awaitFollowWaveCompleted();

        // the in-app row landed — the always-on channel
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'FOLLOWED_PROVIDER_NEW_LISTING'",
                Integer.class, consumerUserId);
        assertThat(notifications).isEqualTo(1);
        // the WS push was suppressed by the standing opt-out — the listener
        // has already COMPLETED (the deterministic registry wait), so no
        // push can arrive after this point
        verify(messagingTemplate, never()).convertAndSend(
                eq("/topic/notifications/" + consumerUserId),
                any(com.marketplace.notifications.WebSocketNotification.class));
    }
}
