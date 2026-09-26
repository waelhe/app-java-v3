package com.marketplace.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6 (comprehensive repair plan §10/2.3): the integration guards for the
 * listing category registry — on the REAL Flyway schema (V70), through the
 * REAL resource-server chain:
 * <ol>
 *   <li><b>The public read:</b> an anonymous caller reads the registry in
 *       display order (the storefront's category picker) — the exact-literal
 *       {@code /categories} route wins over the {@code /{id}} template.</li>
 *   <li><b>The write gate:</b> a listing creation carrying an UNKNOWN
 *       category code answers a clean 400 (the free-text era's end) — the
 *       registry's code passes the same gate.</li>
 *   <li><b>The update gate:</b> an existing listing's update to an unknown
 *       category answers the same 400.</li>
 * </ol>
 *
 * <p>Boot pattern follows {@code ListingCompletenessIntegrationTest}: the
 * isolated postgis container via {@code @ServiceConnection}, Flyway enabled,
 * {@code ddl-auto=none}, {@code JdbcTemplate} fixtures, and
 * {@code @MockitoBean CurrentUserProvider} as the ONLY mocked seam — the
 * PROVIDER-role JWT rides the REAL resource-server chain.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class CategoryRegistryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private static final UUID PROVIDER_USER_ID = UUID.randomUUID();
    private static final UUID OWNED_LISTING_ID = UUID.randomUUID();

    @BeforeEach
    void seed() {
        cleanUp();
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                PROVIDER_USER_ID, "s6-provider-subject",
                "s6-provider@example.com", "S6 Provider");
        jdbcTemplate.update(
                "INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted) "
                        + "VALUES (?, 'S6 Provider', null, 'VERIFIED', ?, now(), now(), 0, false)",
                UUID.randomUUID(), PROVIDER_USER_ID);
        jdbcTemplate.update(
                "INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted) "
                        + "VALUES (?, ?, 'S6 Owned', null, 'stay', 1000, 'SAR', 'DRAFT', now(), now(), 0, false)",
                OWNED_LISTING_ID, PROVIDER_USER_ID);
    }

    /**
     * The per-method reset the reference integration tests use
     * (ListingCompletenessIntegrationTest#cleanUp, verbatim shape): the class
     * seeds fixed IDs for every test method on ONE container, so the previous
     * method's rows must go first — child tables before parents — or the
     * second seed dies on users_pkey. CI round 1 measured exactly that
     * cascade (only the first method to run survived; CodeRabbit round 1
     * flagged the same). The provider_listings sweep is by provider_id so the
     * listing the creation test POSTs is cleaned with the seed's own rows.
     */
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM provider_listings WHERE provider_id = ?", PROVIDER_USER_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id = ?", PROVIDER_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", PROVIDER_USER_ID);
    }

    @Test
    void anonymousCallerReadsTheRegistryInDisplayOrder() throws Exception {
        mockMvc.perform(get("/api/v1/listings/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$[0].code").value("stay"))
                .andExpect(jsonPath("$[0].nameEn").value("Stay"))
                .andExpect(jsonPath("$[0].nameAr").value("إقامة"));
    }

    @Test
    void listingCreationWithUnknownCategoryAnswersCleanBadRequest() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(PROVIDER_USER_ID);

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "S6 New Listing", "category": "bogus", "priceCents": 35000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.stringContainsInOrder("Unknown listing category", "bogus")));
    }

    @Test
    void listingCreationWithARegisteredCategoryPassesTheGate() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(PROVIDER_USER_ID);

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "S6 Registered Category", "category": "stay", "priceCents": 35000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("stay"));
    }

    @Test
    void listingUpdateToAnUnknownCategoryAnswersCleanBadRequest() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(PROVIDER_USER_ID);

        mockMvc.perform(put("/api/v1/listings/{id}", OWNED_LISTING_ID)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "S6 Owned", "category": "made-up", "priceCents": 1000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.stringContainsInOrder("Unknown listing category", "made-up")));
    }

    /**
     * CodeRabbit round 1, adopted — the legacy-preservation leg: a listing
     * whose stored category predates the registry (the FK debt's documented
     * population) stays UPDATABLE while submitting that same unchanged value.
     * The seed's own row is re-seeded here with a legacy code ("home" — a
     * value the registry does NOT carry) for this test only, and the update
     * submits it unchanged: 200, not the frozen-listing 400.
     */
    @Test
    void listingUpdateSubmittingTheStoredLegacyCategoryUnchangedIsPreserved() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(PROVIDER_USER_ID);
        jdbcTemplate.update(
                "UPDATE provider_listings SET category = 'home' WHERE id = ?", OWNED_LISTING_ID);

        mockMvc.perform(put("/api/v1/listings/{id}", OWNED_LISTING_ID)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "S6 Owned Relisted", "category": "home", "priceCents": 1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("home"));
    }
}
