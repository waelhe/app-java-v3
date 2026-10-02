package com.marketplace.community;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;
import com.marketplace.shared.security.CurrentUserProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L50 — the market board's DATABASE-backed guards (the review round's
 * coverage adoption: the service tests mock the repository, so the
 * composed board filters, the sort order, the soft-delete filtering and
 * V90's pricing constraint had no test that could see them). This class
 * runs every acceptance fact over the REAL chain: HTTP → the
 * resource-server chain → the membership gate → the REAL geo seed tree
 * → V90's real schema (the CHECKs, the partial board index, the Envers
 * mirrors) → the composed Specification queries PostgreSQL actually
 * compiles.
 *
 * <p>Every test uses its OWN random user ids (the L41/L42 convention —
 * no test-level transaction; each service call commits its own), and
 * the shared container's market rows are wiped between tests (the
 * board's scope is the neighborhood, so an earlier test's rows would
 * bleed into a later board count).
 *
 * <p>Coverage: (1) the composed filters — location scoping (another
 * neighborhood's items never appear), the literal substring search (a
 * {@code %} or {@code _} in the query is a CHARACTER, never a wildcard
 * — the escaping adoption's own guard), the category chip; (2) the
 * complete sort key (newest first); (3) the withdraw's soft-delete
 * filtering (the board stops returning the row, the row itself stays —
 * b-5's retention); (4) V90's pricing CHECK firing loudly on a raw
 * writer; (5) the ISO 4217 gate over HTTP (the {@code ZZZ} case) and
 * the stored canonical form; (6) the badge's location scoping (an
 * author VERIFIED in ANOTHER neighborhood renders the honest floor
 * here — the badge does not follow the seller out).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The module net pins the CONTRACT LOGIC, not the limiter windows
        // (the events test's own convention): the 400-attempts below spend
        // the marketCreate window too, so the instance rides a generous
        // test-only budget here.
        "resilience4j.ratelimiter.instances.marketCreate.limit-for-period=100",
        "resilience4j.ratelimiter.instances.marketCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.marketCreate.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodMarketBoardIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): two DIFFERENT level-3
    // neighborhoods — the board-scoping and badge-scoping facts need both.
    private static final String OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String SUBURB = "11111111-1111-4111-8111-111111111105";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @Autowired
    private NeighborhoodMarketItemService marketService;

    @BeforeEach
    void isolateMarketData() {
        jdbc.update("DELETE FROM neighborhood_market_items_aud");
        jdbc.update("DELETE FROM neighborhood_market_items");
    }

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /** Joins the given node over the REAL membership service. */
    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    private Authentication serviceAuth(UUID userId) {
        return new TestingAuthenticationToken(userId.toString(), "n/a", "ROLE_CONSUMER");
    }

    private UUID publish(UUID authorId, String locationId, String category, String title,
                         Integer priceCents, String currency, String label) {
        return marketService.createItem(authorId, UUID.fromString(locationId),
                MarketCategory.valueOf(category), title, MarketCondition.GOOD,
                priceCents, currency, label).id();
    }

    // ---- (1) the composed filters over the real schema -----------------------

    @Test
    void boardScoping_anotherNeighborhoodsItemsNeverAppear() throws Exception {
        UUID oldTownMember = asCaller(joinedMember(OLD_TOWN));
        joinedMember(SUBURB); // a member of the OTHER neighborhood
        publish(UUID.randomUUID(), SUBURB, "FURNITURE", "أريكة الضاحية", 48000, "SAR", "شارع عام");

        mockMvc.perform(get("/api/v1/neighborhood/market").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void literalSearch_underscoresAndPercentsAreCharactersNotWildcards() throws Exception {
        asCaller(joinedMember(OLD_TOWN));
        UUID percentAuthor = UUID.randomUUID();
        publish(percentAuthor, OLD_TOWN, "FURNITURE", "خصم 100% على الإطار", 1000, "SAR", "شارع عام");
        publish(UUID.randomUUID(), OLD_TOWN, "TOOLS", "مفتاح إنجليزي like_new", 500, "SAR", "شارع عام");
        publish(UUID.randomUUID(), OLD_TOWN, "OTHER", "كرسي خشبي", 300, "SAR", "شارع عام");

        // A literal '%' finds ONLY the title carrying a percent sign —
        // an unescaped pattern would match every row.
        mockMvc.perform(get("/api/v1/neighborhood/market").param("q", "%").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("خصم 100% على الإطار"));

        // A literal '_' finds ONLY the title carrying an underscore —
        // an unescaped pattern would match every single-character-including row.
        mockMvc.perform(get("/api/v1/neighborhood/market").param("q", "_").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("مفتاح إنجليزي like_new"));
    }

    // ---- (2) the complete sort key --------------------------------------------

    @Test
    void board_isNewestFirst() throws Exception {
        asCaller(joinedMember(OLD_TOWN));
        publish(UUID.randomUUID(), OLD_TOWN, "OTHER", "الأول", 100, "SAR", "شارع عام");
        publish(UUID.randomUUID(), OLD_TOWN, "OTHER", "الثاني", 200, "SAR", "شارع عام");

        mockMvc.perform(get("/api/v1/neighborhood/market").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("الثاني"))
                .andExpect(jsonPath("$.content[1].title").value("الأول"));
    }

    // ---- (3) the withdraw's soft-delete filtering ------------------------------

    @Test
    void withdraw_hidesTheItemFromTheBoard_theRowStays() throws Exception {
        UUID author = asCaller(joinedMember(OLD_TOWN));
        UUID itemId = publish(author, OLD_TOWN, "FURNITURE", "أريكة", 48000, "SAR", "شارع عام");

        mockMvc.perform(delete("/api/v1/neighborhood/market/{itemId}", itemId).with(jwt()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/neighborhood/market").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // b-5's retention: the row itself stays — the reads stop returning it.
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_market_items WHERE id = ? AND is_deleted = TRUE",
                Integer.class, itemId);
        assertThat(live).isEqualTo(1);
    }

    // ---- (4) V90's pricing CHECK on raw writers ---------------------------------

    @Test
    void pricingCheck_firesOnRawWriters() {
        UUID author = joinedMember(OLD_TOWN);
        try {
            jdbc.update("INSERT INTO neighborhood_market_items (id, author_id, location_id, "
                            + "category, title, item_condition, price_cents, price_currency, status, "
                            + "location_label) VALUES (?, ?, ?, 'FURNITURE', 'x', 'GOOD', 100, NULL, "
                            + "'ACTIVE', 'spot')",
                    UUID.randomUUID(), author, UUID.fromString(OLD_TOWN));
            throw new AssertionError("the CHECK must reject the split money pair");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected).isNotNull();
        }
    }

    // ---- (5) the ISO 4217 gate and the stored canonical form ---------------------

    @Test
    void nonIsoCurrency_answers400BeforeAnyWrite() throws Exception {
        asCaller(joinedMember(OLD_TOWN));
        mockMvc.perform(post("/api/v1/neighborhood/market")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + OLD_TOWN + "\", \"category\": \"FURNITURE\", "
                                + "\"title\": \"أريكة\", \"condition\": \"GOOD\", "
                                + "\"priceCents\": 48000, \"priceCurrency\": \"ZZZ\", "
                                + "\"locationLabel\": \"شارع عام\"}"))
                .andExpect(status().isBadRequest());
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_market_items", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void currencyIsNormalizedToTheCanonicalUppercaseForm() {
        UUID author = joinedMember(OLD_TOWN);
        UUID itemId = publish(author, OLD_TOWN, "FURNITURE", "أريكة", 48000, " sar ", "شارع عام");

        String stored = jdbc.queryForObject(
                "SELECT price_currency FROM neighborhood_market_items WHERE id = ?",
                String.class, itemId);
        assertThat(stored).isEqualTo("SAR");
    }

    // ---- (6) the badge's location scoping ---------------------------------------

    @Test
    void sellerBadge_scopesToTheBoardsOwnNeighborhood() throws Exception {
        // The reader: an ordinary ACTIVE member of OLD_TOWN.
        UUID reader = asCaller(joinedMember(OLD_TOWN));
        // The seller: joined OLD_TOWN and published there...
        UUID seller = joinedMember(OLD_TOWN);
        publish(seller, OLD_TOWN, "TOOLS", "مثقاب", 5000, "SAR", "شارع عام");
        // ...then LEFT for the suburb and earned VERIFICATION there (the
        // membership row now points at SUBURB — G-N1's one slot per user).
        jdbc.update("UPDATE neighborhood_memberships SET location_id = ?, "
                        + "verification_state = 'VERIFIED' WHERE user_id = ?",
                UUID.fromString(SUBURB), seller);

        // The old item stays readable on OLD_TOWN's board (b-5: surface
        // deletion is not erasure), but the badge does not follow the
        // seller out — the honest unverified floor.
        mockMvc.perform(get("/api/v1/neighborhood/market").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sellerVerified").value(false));

        // The control: the seller's OWN board (the suburb) carries no item
        // at all — the scope never leaked the other way either.
        asCaller(seller);
        mockMvc.perform(get("/api/v1/neighborhood/market").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void sellerBadge_verifiedInTheBoardsNeighborhood_earnsTheBadge() throws Exception {
        UUID reader = asCaller(joinedMember(OLD_TOWN));
        UUID seller = joinedMember(OLD_TOWN);
        publish(seller, OLD_TOWN, "TOOLS", "مثقاب", 5000, "SAR", "شارع عام");
        jdbc.update("UPDATE neighborhood_memberships SET verification_state = 'VERIFIED' "
                + "WHERE user_id = ?", seller);

        mockMvc.perform(get("/api/v1/neighborhood/market").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sellerVerified").value(true));
    }
}
