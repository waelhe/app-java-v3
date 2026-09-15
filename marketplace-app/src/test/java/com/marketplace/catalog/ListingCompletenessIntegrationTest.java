package com.marketplace.catalog;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L38 (realestate systems plan §5 — listing completeness score): the
 * acceptance criteria over the REAL chain — HTTP → the security filter
 * chain (the L38 carve-out line vs the blanket public listings GET line)
 * → the controller → the REAL {@code CatalogService} ownership gate →
 * {@code ListingCompletenessResponse}'s documented equation over the REAL
 * Flyway schema (provider_listings + property_details + media_assets).
 *
 * <p>Criterion 1 — the fully-completed listing answers 100 with all four
 * component flags. Criterion 2 — the boundary values of the documented
 * equation (25 for the core-only listing, 75 for the everything-but-photos
 * listing, 0 for the bare-mandatory listing). Criterion 3 — the score is
 * always fresh: a photo inserted between two reads is visible on the very
 * next read (no cache — the plan's "قراءة عابرة دائمًا طازجة").
 *
 * <p><b>The id-space fact (A1/V2):</b> {@code provider_listings.provider_id}
 * and {@code media_assets.provider_id} reference {@code users(id)} — the
 * seeded rows carry the owner's USER id.
 *
 * <p>Boot pattern follows {@code ProviderPublicPageIntegrationTest} +
 * {@code LeadsIntegrationTest}: the isolated postgis container via
 * {@code @ServiceConnection}, Flyway enabled, {@code ddl-auto=none}, and
 * {@code @MockitoBean CurrentUserProvider} as the ONLY mocked seam — the
 * role-carrying JWT rides the REAL resource-server chain (the
 * {@code SavedSearchIntegrationTest} pattern: a bare {@code jwt()} carries
 * no authorities, so the PROVIDER role rides {@code .authorities(...)}).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ListingCompletenessIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID FOREIGN_USER_ID = UUID.randomUUID();

    /** description + 1 UPLOADED photo + property with location → 100. */
    private static final UUID FULL_LISTING_ID = UUID.randomUUID();
    /** description only — the plan's criterion-2 low score 25. */
    private static final UUID PARTIAL_LISTING_ID = UUID.randomUUID();
    /** description + property with location + a PENDING_UPLOAD photo only → 75. */
    private static final UUID PENDING_LISTING_ID = UUID.randomUUID();
    /** bare schema-mandatory title/price — the floor score 0. */
    private static final UUID EMPTY_LISTING_ID = UUID.randomUUID();
    /** ACTIVE — the public detail surface's anonymous-200 proof. */
    private static final UUID PUBLIC_ACTIVE_LISTING_ID = UUID.randomUUID();

    /** A fixed geo seed node (R__seed_geo_qudsaya — Qudsayya city, level 2). */
    private static final UUID QUDSAYYA_CITY =
            java.util.UUID.fromString("11111111-1111-4111-8111-111111111103");

    @BeforeEach
    void seedTheDataset() {
        cleanUp();

        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                OWNER_USER_ID, "l38-owner-" + OWNER_USER_ID,
                "l38-owner-" + OWNER_USER_ID + "@example.com", "L38 Owner");
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                FOREIGN_USER_ID, "l38-foreign-" + FOREIGN_USER_ID,
                "l38-foreign-" + FOREIGN_USER_ID + "@example.com", "L38 Foreign");

        // The owner's profile: verifyOwnership resolves the LISTING's
        // provider through findByUserId (the A1 users.id space).
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L38 Owner Estates', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), OWNER_USER_ID);

        // The listings (provider_listings.provider_id = users.id, A1):
        // all DRAFT (the completeness surface is the provider's own read —
        // it works on any status; the plan's score guides completion
        // BEFORE activation) except the public-detail proof row.
        listing(FULL_LISTING_ID, "DRAFT", "Full villa description");
        listing(PARTIAL_LISTING_ID, "DRAFT", "Partial listing description");
        listing(PENDING_LISTING_ID, "DRAFT", "Pending photo listing description");
        listing(EMPTY_LISTING_ID, "DRAFT", null);
        listing(PUBLIC_ACTIVE_LISTING_ID, "ACTIVE", "Public active listing");

        // The L31 property blocks (plain UUID columns — the V48 discipline,
        // no cross-module FK): FULL and PENDING carry the L30 location.
        property(FULL_LISTING_ID);
        property(PENDING_LISTING_ID);

        // The media: FULL has one storage-verified photo; PENDING has ONLY
        // a presigned-but-never-confirmed row (the UPLOADED-only contract).
        media(FULL_LISTING_ID, "UPLOADED", 1);
        media(PENDING_LISTING_ID, "PENDING_UPLOAD", 1);
        media(PUBLIC_ACTIVE_LISTING_ID, "UPLOADED", 1);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM media_assets WHERE provider_id = ?", OWNER_USER_ID);
        jdbcTemplate.update("DELETE FROM property_details WHERE provider_id = ?", OWNER_USER_ID);
        jdbcTemplate.update(
                "DELETE FROM provider_listings WHERE id IN (?, ?, ?, ?, ?)",
                FULL_LISTING_ID, PARTIAL_LISTING_ID, PENDING_LISTING_ID,
                EMPTY_LISTING_ID, PUBLIC_ACTIVE_LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id = ?", OWNER_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", OWNER_USER_ID, FOREIGN_USER_ID);
    }

    /** Criterion 1 — the full listing answers 100 with every flag true. */
    @Test
    void fullListing_answers100() throws Exception {
        asOwner();

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", FULL_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.coreFieldsPresent").value(true))
                .andExpect(jsonPath("$.photosPresent").value(true))
                .andExpect(jsonPath("$.propertyDetailsPresent").value(true))
                .andExpect(jsonPath("$.locationPresent").value(true));
    }

    /**
     * Criterion 2 — the documented equation's boundary values on the REAL
     * data: the core-only listing answers the LOW quarter score 25 (the
     * plan's own example — no photos, no details, no location), the
     * everything-but-photos listing answers 75, and the bare-mandatory
     * listing answers the floor 0.
     */
    @Test
    void boundaryScores_followTheDocumentedEquation() throws Exception {
        asOwner();

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", PARTIAL_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(25))
                .andExpect(jsonPath("$.coreFieldsPresent").value(true))
                .andExpect(jsonPath("$.photosPresent").value(false))
                .andExpect(jsonPath("$.propertyDetailsPresent").value(false))
                .andExpect(jsonPath("$.locationPresent").value(false));

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", PENDING_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(75))
                .andExpect(jsonPath("$.photosPresent").value(false));

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", EMPTY_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(0))
                .andExpect(jsonPath("$.coreFieldsPresent").value(false));
    }

    /**
     * The UPLOADED-only contract in the real repository: the PENDING
     * listing's sole asset row never earns the photo quarter — a presigned
     * URL that was never storage-confirmed is not a photo the gallery can
     * display (MediaLookupPort's documented contract).
     */
    @Test
    void pendingUpload_neverCounts() throws Exception {
        asOwner();

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", PENDING_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(75))
                .andExpect(jsonPath("$.photosPresent").value(false));
    }

    /**
     * Criterion 3 — always fresh: the partial listing reads 25, a photo
     * lands (the storage-confirmed row the upload flow would have written),
     * and the VERY NEXT read answers 50 with the photo quarter earned. No
     * cache, no invalidation — the plan's "قراءة عابرة دائمًا طازجة".
     */
    @Test
    void scoreIsAlwaysFresh_photoLandsBetweenReads() throws Exception {
        asOwner();

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", PARTIAL_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(25))
                .andExpect(jsonPath("$.photosPresent").value(false));

        media(PARTIAL_LISTING_ID, "UPLOADED", 2);

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", PARTIAL_LISTING_ID)
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(50))
                .andExpect(jsonPath("$.photosPresent").value(true));
    }

    /**
     * The L38 carve-out line: the score is the provider's OWN read — an
     * anonymous caller answers 401 through the resource-server chain while
     * the blanket public listings GET line stays intact for the plain
     * detail surface (the anonymous 200 in the sibling test).
     */
    @Test
    void completenessRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}/completeness", FULL_LISTING_ID))
                .andExpect(status().isUnauthorized());
    }

    /** The blanket public line is untouched: the plain detail stays public. */
    @Test
    void publicDetailSurface_staysAnonymousReadable() throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", PUBLIC_ACTIVE_LISTING_ID))
                .andExpect(status().isOk());
    }

    /** The ownership gate: a foreign provider answers 403, not the score. */
    @Test
    void foreignProvider_answers403() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(FOREIGN_USER_ID);

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", FULL_LISTING_ID)
                        .with(providerJwt("l38-foreign")))
                .andExpect(status().isForbidden());
    }

    /**
     * L38 (CodeRabbit round-1 adoption): the admin-family gate — an ADMIN
     * token reads a listing he does NOT own (the verifyOwnership admin
     * bypass, the archive family's own contract in this service; the same
     * hasAnyRole('PROVIDER','ADMIN') gate).
     */
    @Test
    void admin_readsAnyListingCompleteness() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(FOREIGN_USER_ID);
        when(currentUserProvider.isAdmin(any())).thenReturn(true);

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", FULL_LISTING_ID)
                        .with(jwt().jwt(j -> j.subject("l38-admin"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(100));
    }

    /** An unknown listing answers the ownership read's honest 404. */
    @Test
    void unknownListing_answers404() throws Exception {
        asOwner();

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", UUID.randomUUID())
                        .with(providerJwt("l38-owner")))
                .andExpect(status().isNotFound());
    }

    // ---- helpers --------------------------------------------------------------

    private void asOwner() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);
    }

    /**
     * The role-carrying JWT (the SavedSearchIntegrationTest pattern): the
     * surface is both authenticated (the carve-out line) and
     * PROVIDER-gated (@PreAuthorize on the service) — a bare jwt() carries
     * no authorities and would answer a plain 403.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor providerJwt(
            String subject) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_PROVIDER"));
    }

    private void listing(UUID id, String status, String description) {
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, 'L38 Listing', ?, 'APARTMENT', 1000, 'SAR', ?, now(), now(), 0, false)
                """,
                id, OWNER_USER_ID, description, status);
    }

    private void property(UUID listingId) {
        jdbcTemplate.update(
                """
                INSERT INTO property_details (id, listing_id, provider_id, purpose, property_type, area_m2, location_id, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, ?, 'RENT', 'APARTMENT', 120, ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), listingId, OWNER_USER_ID, QUDSAYYA_CITY);
    }

    private void media(UUID listingId, String status, int position) {
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (id, listing_id, provider_id, object_key, content_type, size_bytes, status, position, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, ?, ?, 'image/jpeg', 2048, ?, ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), listingId, OWNER_USER_ID,
                "listings/" + listingId + "/l38-" + UUID.randomUUID() + ".jpg", status, position);
    }
}
