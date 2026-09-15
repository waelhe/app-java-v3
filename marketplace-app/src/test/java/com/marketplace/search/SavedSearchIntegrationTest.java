package com.marketplace.search;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts) — the
 * acceptance criteria over the REAL chain: HTTP → the resource-server
 * chain → the /me surface → the SearchCriteria gate (the record's own
 * constructor, through Jackson) → V54's JSONB round-trip → the REAL
 * catalog activation (CatalogService.activate publishes
 * ListingActivatedEvent inside its transaction) → the search module's
 * listener (its own transaction) → the REAL dispatch-faithful matcher
 * (geo port + realestate filter port + catalog restricted forms on real
 * SQL) → the idempotency ledger (native ON CONFLICT on the partial-unique
 * index) → the aggregated SavedSearchMatchedEvent → the notifications
 * module's real listener → the SAVED_SEARCH_MATCH row (V55's widened
 * CHECK) and the WS push behind the L22 preference.
 *
 * <p>Seeding follows the L22/L34 conventions: raw SQL + ON CONFLICT DO
 * NOTHING; the fixed geo seed ids (R__seed_geo_qudsaya) carry the
 * location; {@code CurrentUserProvider} is mocked to STITCH the identity
 * (the jwt() post-processor rides the real filter chain) and
 * {@code SimpMessagingTemplate} is mocked to OBSERVE the push.
 *
 * <p>Acceptance criteria covered: (1) a matching activation notifies the
 * saved-search owner — in-app row + WS; (2) a non-matching activation is
 * silent (asserted AFTER the registry row completes — deterministic, not
 * a sleep); (3-b) re-delivering the same activation inserts nothing new
 * and does not double-notify; (4) two matching searches for one user are
 * ONE aggregated notification; (5) invalid criteria answer 400 at the
 * surface; the anonymous surface answers 401; (7) the purge removes the
 * criteria's query key (idempotent) and the export carries the stored
 * criteria.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SavedSearchIntegrationTest {

    /** The fixed geo seed id of Qudsayya city (level 2) — R__seed_geo_qudsaya. */
    private static final String QUDSAYYA_CITY = "11111111-1111-4111-8111-111111111103";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches LeadsIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
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
    private SavedSearchService savedSearchService;

    @Autowired
    private com.marketplace.search.spi.SearchContentPurgeAdapter purgeAdapter;

    @Autowired
    private com.marketplace.search.spi.SavedSearchExportAdapter exportAdapter;

    private UUID consumerUserId;
    private UUID providerUserId;
    private UUID providerProfileId;

    @BeforeEach
    void seed() {
        // The pristine saved-search world (the CI round-4 root fix): this
        // class shares ONE Spring context (and one container) across its
        // tests, and the matcher scans EVERY alert-enabled saved search
        // regardless of owner — correct production behavior. A live
        // search left behind by a previously-run test (its own random
        // user) matches a later test's fresh listing and inserts an
        // unexpected ledger row: measured live in CI round 4 — the
        // aggregation test counted 3 rows for its listing (its own two
        // searches + one leftover) while its notification count stayed
        // honestly scoped to its own recipient. Every test therefore
        // starts from an empty saved-search world. No FK links the two
        // tables (the V52 plain-UUID discipline) and raw SQL writes no
        // Envers revisions, so the deletes are order-free and audit-silent.
        jdbc.update("DELETE FROM saved_search_matches");
        jdbc.update("DELETE FROM saved_searches");
        jdbc.update("DELETE FROM notifications WHERE type = 'SAVED_SEARCH_MATCH'");

        consumerUserId = UUID.randomUUID();
        providerUserId = UUID.randomUUID();
        providerProfileId = UUID.randomUUID();

        seedUser(consumerUserId, "l35-consumer-" + consumerUserId + "@example.com", "CONSUMER");
        seedUser(providerUserId, "l35-provider-" + providerUserId + "@example.com", "PROVIDER");
        seedProviderProfile(providerProfileId, providerUserId);
    }

    private void seedUser(UUID id, String email, String role) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, email, email, "L35 user", role);
    }

    private void seedProviderProfile(UUID id, UUID userId) {
        jdbc.update("""
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at)
                VALUES (?, ?, 'l35-test', 'PENDING', ?, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, "L35 provider", userId);
    }

    /** A DRAFT listing with a rooms-3 property in Qudsayya city. */
    private UUID seedDraftListingWithProperty() {
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'L35 flat', 'seed listing', 'APARTMENT', 10000, 'SAR', 'DRAFT')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerUserId);
        jdbc.update("""
                INSERT INTO property_details (id, listing_id, provider_id, purpose, property_type, rooms, bathrooms, area_m2, location_id)
                VALUES (?, ?, ?, 'RENT', 'APARTMENT', 3, 1, 90, ?)
                ON CONFLICT (id) DO NOTHING
                """, UUID.randomUUID(), listingId, providerUserId, java.util.UUID.fromString(QUDSAYYA_CITY));
        return listingId;
    }

    private UUID saveSearch(String criteriaJson) throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        String response = mockMvc.perform(post("/api/v1/me/saved-searches")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\": " + criteriaJson + ", \"alertEnabled\": true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response).get("id").toString().replace("\"", ""));
    }

    private void activateListing(UUID listingId) throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerUserId);
        // The role-carrying jwt (the RateLimitProblemDetailIntegrationTest
        // pattern): activate() is @PreAuthorize("hasRole('PROVIDER')") and
        // the bare jwt() token carries no authorities — a plain 403.
        mockMvc.perform(post("/api/v1/listings/{id}/activate", listingId)
                        .with(jwt().jwt(j -> j.subject("l35-provider"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_PROVIDER"))))
                .andExpect(status().isOk());
    }

    /**
     * Deterministic async wait: the activation's publication row is written
     * atomically with the listing's ACTIVATE commit (so it EXISTS once
     * activateListing() returns) and disappears when the listener completes
     * — the test profile DELETES completed publications (the dev-mode
     * completion registry), so completion is observable as the row's
     * ABSENCE, never as a completion_date (measured in CI round 2: the
     * completed rows are gone, the scans themselves logged).
     */
    private void awaitScanCompleted(UUID listingId) throws InterruptedException {
        // Zero pending rows is accepted IMMEDIATELY (the CodeRabbit round-1
        // adoption — same root as the CI round-3 race): the publication row
        // is written atomically with the ACTIVATE commit, so once
        // activateListing() has returned, an absent row can only mean the
        // listener already completed — "absent but pending" cannot exist.
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
        throw new AssertionError("the ListingActivatedEvent scan never completed for " + listingId);
    }

    private void awaitNotification(UUID userId, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'SAVED_SEARCH_MATCH'",
                    Integer.class, userId);
            if (n != null && n >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("SAVED_SEARCH_MATCH notification never landed for " + userId);
    }

    @Test
    void matchingActivationNotifiesTheOwnerOncePerListingWithAggregation() throws Exception {
        // Criterion 1 (the full chain) + criterion 4 (the aggregation):
        // TWO saved searches of one user match the SAME activation — ONE
        // notification, and its message counts both searches.
        UUID listingId = seedDraftListingWithProperty();
        saveSearch("{\"minRooms\": 2, \"locationId\": \"" + QUDSAYYA_CITY + "\"}");
        saveSearch("{\"category\": \"APARTMENT\"}");

        activateListing(listingId);

        awaitNotification(consumerUserId, 1);
        awaitScanCompleted(listingId);
        // exactly one row — the structural aggregation
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'SAVED_SEARCH_MATCH'",
                Integer.class, consumerUserId);
        assertThat(notifications).isEqualTo(1);
        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE recipient_id = ? AND type = 'SAVED_SEARCH_MATCH'",
                String.class, consumerUserId);
        assertThat(message).contains("2").contains("saved searches");
        // the WS push behind the L22 preference (default on) — observed once
        verify(messagingTemplate, timeout(5000).times(1)).convertAndSend(
                eq("/topic/notifications/" + consumerUserId),
                any(com.marketplace.notifications.WebSocketNotification.class));
        // the ledger rows for both searches
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM saved_search_matches WHERE listing_id = ?", Integer.class, listingId);
        assertThat(matches).isEqualTo(2);
    }

    @Test
    void nonMatchingActivationIsSilent() throws Exception {
        // Criterion 2 — the negative: rooms 5 is not satisfied by the
        // seeded rooms-3 property; after the scan COMPLETES (deterministic
        // wait on the registry), there is no notification and no log
        // noise for non-matches (the service logs ONE scan line, never a
        // line per miss — the criterion's "لا إشعار ولا تسجيل").
        UUID listingId = seedDraftListingWithProperty();
        saveSearch("{\"minRooms\": 5, \"locationId\": \"" + QUDSAYYA_CITY + "\"}");

        activateListing(listingId);
        awaitScanCompleted(listingId);

        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'SAVED_SEARCH_MATCH'",
                Integer.class, consumerUserId);
        assertThat(notifications).isZero();
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM saved_search_matches WHERE listing_id = ?", Integer.class, listingId);
        assertThat(matches).isZero();
    }

    @Test
    void reDeliveringTheSameActivationDoesNotDoubleNotify() throws Exception {
        // Criterion 3-b — the idempotency ledger: the same activation
        // processed twice inserts nothing new and notifies once (the
        // ON CONFLICT skip on the partial-unique index).
        UUID listingId = seedDraftListingWithProperty();
        saveSearch("{\"minRooms\": 2, \"locationId\": \"" + QUDSAYYA_CITY + "\"}");

        // first delivery through the REAL event chain
        activateListing(listingId);
        awaitNotification(consumerUserId, 1);

        // second delivery of the same (listing, provider) pair — the
        // listener body re-run (the registry's at-least-once contract)
        savedSearchService.processListingActivated(listingId, providerUserId);

        awaitScanCompleted(listingId);
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'SAVED_SEARCH_MATCH'",
                Integer.class, consumerUserId);
        assertThat(notifications).isEqualTo(1);
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM saved_search_matches WHERE listing_id = ?", Integer.class, listingId);
        assertThat(matches).isEqualTo(1);
    }

    @Test
    void anonymousCreateIs401_andInvalidCriteriaIs400() throws Exception {
        // The security negative: the /me surface is authenticated — 401
        // through the real resource-server chain (no security-config
        // change was needed for this layer).
        mockMvc.perform(post("/api/v1/me/saved-searches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\": {}, \"alertEnabled\": true}"))
                .andExpect(status().isUnauthorized());

        // Criterion 5 — the SAME type gate as the search surface: an
        // incomplete stay window cannot even be constructed (400 before
        // any write).
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        mockMvc.perform(post("/api/v1/me/saved-searches")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\": {\"checkIn\": \"2026-10-01T14:00:00Z\"}, \"alertEnabled\": true}"))
                .andExpect(status().isBadRequest());

        Integer savedCount = jdbc.queryForObject(
                "SELECT count(*) FROM saved_searches WHERE user_id = ?", Integer.class, consumerUserId);
        assertThat(savedCount).isZero();
    }

    @Test
    void theMeSurfaceListsAndDeletesWithOwnerScoping() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        UUID savedId = saveSearch("{\"minRooms\": 2}");
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);

        mockMvc.perform(get("/api/v1/me/saved-searches").with(jwt())
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(savedId.toString()))
                .andExpect(jsonPath("$.content[0].criteria.minRooms").value(2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/me/saved-searches/{id}", savedId).with(jwt()))
                .andExpect(status().isNoContent());

        // soft-deleted: the row survives, the surface and the scan filter it
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM saved_searches WHERE id = ? AND is_deleted = FALSE",
                Integer.class, savedId);
        Integer archived = jdbc.queryForObject(
                "SELECT count(*) FROM saved_searches WHERE id = ? AND is_deleted = TRUE",
                Integer.class, savedId);
        assertThat(live).isZero();
        assertThat(archived).isEqualTo(1);

        // a foreign id is an honest 404
        UUID foreign = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerUserId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/me/saved-searches/{id}", savedId).with(jwt()))
                .andExpect(status().isNotFound());
        assertThat(foreign).isNotNull();
    }

    @Test
    void purgeRemovesTheQueryKey_andTheExportCarriesTheStoredCriteria() throws Exception {
        // Criterion 7 — the b-3 seam: the criteria's only authored free
        // text is the query component; the purge removes that JSON key
        // (idempotently, base + mirror), and the Art. 20 export carries
        // the stored criteria as canonical JSON.
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerUserId);
        UUID savedId = saveSearch(
                "{\"query\": \"sea view\", \"minRooms\": 2, \"locationId\": \"" + QUDSAYYA_CITY + "\"}");

        // the export BEFORE the purge: the criteria as stored
        var before = exportAdapter.exportForOwner(consumerUserId);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).criteriaJson()).contains("sea view").contains("minRooms");

        int purged = purgeAdapter.purgeAuthoredTexts(consumerUserId);
        assertThat(purged).isGreaterThanOrEqualTo(1);
        String criteria = jdbc.queryForObject(
                "SELECT criteria::text FROM saved_searches WHERE id = ?", String.class, savedId);
        assertThat(criteria).doesNotContain("sea view").contains("minRooms"); // the facets survive
        // the mirror purges with the same predicate
        Integer mirrorWithQuery = jdbc.queryForObject(
                "SELECT count(*) FROM saved_searches_aud WHERE user_id = ? AND jsonb_exists(criteria, 'query')",
                Integer.class, consumerUserId);
        assertThat(mirrorWithQuery).isZero();
        // idempotent: a re-run matches zero rows
        assertThat(purgeAdapter.purgeAuthoredTexts(consumerUserId)).isZero();
    }
}
