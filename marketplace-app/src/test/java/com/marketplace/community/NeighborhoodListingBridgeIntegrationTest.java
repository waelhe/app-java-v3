package com.marketplace.community;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L46 (neighborhood community plan §5 — the community realestate bridge)
 * — the acceptance criteria over the REAL chain: HTTP activation → the
 * resource-server chain → the REAL catalog activation
 * (CatalogService.activate publishes ListingActivatedEvent inside its
 * transaction — one publisher, two consumers) → the community module's
 * bridge listener (its own transaction) → the REAL property resolution
 * (PropertyDetailsPort on real SQL) → the ACTIVE-membership query (the
 * @SoftDelete filter on real SQL) → the per-member
 * NewListingInNeighborhoodEvent publications → the notifications module's
 * real listener → the NEW_LISTING_IN_NEIGHBORHOOD row (V63's widened
 * CHECK) and the WS push behind the L22 preference.
 *
 * <p>Seeding follows the L35/L42 conventions: raw SQL + ON CONFLICT DO
 * NOTHING; the fixed geo seed ids (R__seed_geo_qudsaya) carry the
 * level-3 neighborhoods; {@code CurrentUserProvider} is mocked to STITCH
 * the identity (the jwt() post-processor rides the real filter chain)
 * and {@code SimpMessagingTemplate} is mocked to OBSERVE the push.
 *
 * <p>Acceptance criteria covered: (1) an activation in a member's
 * neighborhood notifies that member — in-app row + WS; (2) a
 * non-real-estate listing is the documented skip (zero notifications,
 * asserted AFTER the bridge completes — deterministic, never a sleep);
 * (3) the registry's at-least-once contract — a re-delivered activation
 * still reaches the member (the L35 3-b re-run shape); (4) the
 * publisher's own membership is the one member not alerted; (5) a
 * neighborhood with no members is silent — zero notifications, zero
 * errors, the bridge completes.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodListingBridgeIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): the level-3
    // neighborhoods the membership gate admits.
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the house precedent
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
    private NeighborhoodMembershipService membershipService;

    /**
     * Cross-test isolation for the shared container: every test seeds its
     * own members in the SAME fixed seed nodes, and criterion 5 needs a
     * member-free world — so the memberships reset per test (raw SQL,
     * audit-silent, no FK points at them). The L46 notification rows
     * reset with them so the global zero-assertions stay deterministic.
     */
    @BeforeEach
    void isolateNeighborhoodData() {
        jdbc.update("DELETE FROM neighborhood_memberships");
        jdbc.update("DELETE FROM notifications WHERE type = 'NEW_LISTING_IN_NEIGHBORHOOD'");
    }

    private UUID providerUserId;

    private void seedProvider() {
        providerUserId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerUserId, "l46-provider-" + providerUserId + "@example.com",
                "l46-provider-" + providerUserId + "@example.com", "L46 provider");
    }

    /** A DRAFT listing whose property sits in the given level-3 node. */
    private UUID seedDraftListingWithPropertyAt(String locationId) {
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'L46 flat', 'seed listing', 'APARTMENT', 10000, 'SAR', 'DRAFT')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerUserId);
        jdbc.update("""
                INSERT INTO property_details (id, listing_id, provider_id, purpose, property_type, rooms, bathrooms, area_m2, location_id)
                VALUES (?, ?, ?, 'RENT', 'APARTMENT', 3, 1, 90, ?)
                ON CONFLICT (id) DO NOTHING
                """, UUID.randomUUID(), listingId, providerUserId, UUID.fromString(locationId));
        return listingId;
    }

    /** A DRAFT listing with NO property_details row — not real-estate. */
    private UUID seedDraftListingWithoutProperty() {
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'L46 service', 'seed listing', 'CLEANING', 5000, 'SAR', 'DRAFT')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerUserId);
        return listingId;
    }

    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    private void activateListing(UUID listingId) throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerUserId);
        // The role-carrying jwt (the SavedSearchIntegrationTest pattern):
        // activate() is @PreAuthorize("hasRole('PROVIDER')") and the bare
        // jwt() token carries no authorities — a plain 403.
        mockMvc.perform(post("/api/v1/listings/{id}/activate", listingId)
                        .with(jwt().jwt(j -> j.subject("l46-provider"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_PROVIDER"))))
                .andExpect(status().isOk());
    }

    /**
     * Deterministic async wait: the activation's publication row is
     * written atomically with the listing's ACTIVATE commit (so it EXISTS
     * once activateListing() returns) and leaves {@code event_publication}
     * when BOTH consumers complete (the search scanner and the community
     * bridge — the archive completion mode moves completed rows out).
     */
    private void awaitBridgeCompleted() throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Integer pending = jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication "
                            + "WHERE event_type LIKE '%ListingActivatedEvent%'",
                    Integer.class);
            if (pending == null || pending == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the ListingActivatedEvent publications never completed");
    }

    private void awaitNotification(UUID userId, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'NEW_LISTING_IN_NEIGHBORHOOD'",
                    Integer.class, userId);
            if (n != null && n >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("NEW_LISTING_IN_NEIGHBORHOOD notification never landed for " + userId);
    }

    private int bridgeNotificationsFor(UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'NEW_LISTING_IN_NEIGHBORHOOD'",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    @Test
    void criterion1_activationInAMembersNeighborhoodNotifiesThatMember() throws Exception {
        // The full chain: a member of QUDSAYYA_OLD_TOWN, a property in
        // QUDSAYYA_OLD_TOWN, a REAL activation — the member is alerted
        // (in-app row + WS push), and a member of a DIFFERENT
        // neighborhood is not.
        seedProvider();
        UUID member = joinedMember(QUDSAYYA_OLD_TOWN);
        UUID otherNeighborhoodMember = joinedMember(QUDSAYYA_SUBURB);
        UUID listingId = seedDraftListingWithPropertyAt(QUDSAYYA_OLD_TOWN);

        activateListing(listingId);

        awaitNotification(member, 1);
        awaitBridgeCompleted();
        assertThat(bridgeNotificationsFor(member)).isEqualTo(1);
        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE recipient_id = ? AND type = 'NEW_LISTING_IN_NEIGHBORHOOD'",
                String.class, member);
        assertThat(message).contains(listingId.toString());
        // the WS push behind the L22 preference (default on) — observed
        // once, carrying the L46 type
        org.mockito.ArgumentCaptor<com.marketplace.notifications.WebSocketNotification> wsCaptor =
                org.mockito.ArgumentCaptor.forClass(com.marketplace.notifications.WebSocketNotification.class);
        verify(messagingTemplate, timeout(5000).times(1)).convertAndSend(
                eq("/topic/notifications/" + member), wsCaptor.capture());
        assertThat(wsCaptor.getValue().type()).isEqualTo("NEW_LISTING_IN_NEIGHBORHOOD");
        // the other neighborhood's member is silent (the property's node
        // scopes the fan-out) and so is everyone else
        assertThat(bridgeNotificationsFor(otherNeighborhoodMember)).isZero();
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'NEW_LISTING_IN_NEIGHBORHOOD'",
                Integer.class);
        assertThat(total).isEqualTo(1);
    }

    @Test
    void criterion2_nonRealestateListingIsTheDocumentedSkip() throws Exception {
        // A listing with no property_details row has no neighborhood to
        // bridge — even with a member standing by in a seed node, the
        // bridge skips (asserted AFTER the activation completes).
        seedProvider();
        joinedMember(QUDSAYYA_OLD_TOWN);
        UUID listingId = seedDraftListingWithoutProperty();

        activateListing(listingId);
        awaitBridgeCompleted();

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'NEW_LISTING_IN_NEIGHBORHOOD'",
                Integer.class);
        assertThat(total).isZero();
    }

    @Test
    void criterion3_redeliveringTheSameActivationStillReachesTheMember() throws Exception {
        // The registry's at-least-once contract (the L35 3-b re-run
        // shape): the first delivery through the REAL event chain lands
        // the member's notification; a re-delivered activation (the
        // registry re-running the bridge body after a failure) still
        // reaches the member — the match is never silently lost.
        //
        // By design there is NO idempotency ledger on this bridge (the
        // plan's D-C1: activation is a one-time transition — activate()
        // fires once, renew() never does — so re-delivery only happens
        // after a failure, and the member seeing the alert again after a
        // failure-retry is at-least-once's honest cost; the batching that
        // would dedupe is D-C1's declared closure point, not this
        // layer's).
        seedProvider();
        UUID member = joinedMember(QUDSAYYA_OLD_TOWN);
        UUID listingId = seedDraftListingWithPropertyAt(QUDSAYYA_OLD_TOWN);

        // first delivery through the REAL event chain
        activateListing(listingId);
        awaitNotification(member, 1);

        // second delivery — the bridge body re-run (the registry's
        // at-least-once contract)
        int alerted = membershipService.onListingActivated(listingId, providerUserId);
        assertThat(alerted).isEqualTo(1);

        awaitNotification(member, 2);
        assertThat(bridgeNotificationsFor(member)).isEqualTo(2);
    }

    @Test
    void criterion4_publisherMemberIsNotAlertedAboutTheirOwnListing() throws Exception {
        // The conflict-of-interest exclusion: the listing's publisher IS
        // a member of the listing's own neighborhood — the one member the
        // bridge deliberately does not alert — while the OTHER member of
        // the same node still is.
        seedProvider();
        UUID neighbor = joinedMember(QUDSAYYA_OLD_TOWN);
        // the publisher joins the SAME neighborhood as their listing
        membershipService.join(providerUserId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        UUID listingId = seedDraftListingWithPropertyAt(QUDSAYYA_OLD_TOWN);

        activateListing(listingId);

        awaitNotification(neighbor, 1);
        awaitBridgeCompleted();
        assertThat(bridgeNotificationsFor(neighbor)).isEqualTo(1);
        assertThat(bridgeNotificationsFor(providerUserId)).isZero();
    }

    @Test
    void criterion5_neighborhoodWithoutMembersIsSilent() throws Exception {
        // A property in a level-3 node nobody joined: the query itself is
        // the scope — zero notifications, zero errors, and the bridge
        // COMPLETES (the activation's publication leaves the registry).
        seedProvider();
        UUID listingId = seedDraftListingWithPropertyAt(QUDSAYYA_SUBURB);

        activateListing(listingId);
        awaitBridgeCompleted();

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'NEW_LISTING_IN_NEIGHBORHOOD'",
                Integer.class);
        assertThat(total).isZero();
        // no zombie publications either — the silent bridge completed
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE event_type LIKE '%NewListingInNeighborhoodEvent%'",
                Integer.class);
        assertThat(pending).isZero();
    }
}
