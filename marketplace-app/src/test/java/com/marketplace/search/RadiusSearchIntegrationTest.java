package com.marketplace.search;

import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.realestate.PropertyDetails;
import com.marketplace.realestate.PropertyDetailsRepository;
import com.marketplace.realestate.PropertyDetailsRequest;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 (postgis integration plan) integration guard on the REAL migration
 * schema (the §7 lesson — the postgis/postgis image, Flyway enabled, so
 * V1..V50 run exactly as production does). The plan's acceptance criteria
 * for the radius layer live here: a row without coordinates never matches;
 * the near rows match inside the radius; the far row does not; a partial
 * radius presence is a 400 before any query; {@code sort=distance} orders
 * nearest-first; and EXPLAIN (with seqscans disabled for the session — the
 * D-I3-aware form: tiny test tables would otherwise always justify a seq
 * scan, which proves nothing about the index's usability) shows the V50
 * expressive GiST index serving the predicate.
 *
 * <p>The guard protects the two silent-regression classes the plan names:
 * a broken index match (the query stops using the index and nobody
 * notices) and swapped coordinate order (ST_MakePoint(lng, lat) vs
 * (lat, lng) — the distances go absurd and the results still "look
 * plausible"). Both fail loudly here.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RadiusSearchIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private SearchService searchService;

    @Autowired
    private RealestatePropertyFilterPort filterPort;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private PropertyDetailsRepository propertyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    /** FK parent (V2: provider_listings.provider_id references users(id)). */
    private static final UUID PROVIDER_USER_ID = UUID.fromString("11111111-2222-4333-8444-555555555703");

    // The search center — Qudsayya's approximate coordinates (the plan's
    // own smoke-test center). The rows sit at known distances around it:
    // A ~25m, B ~309m, C ~100km, D has no coordinates at all.
    private static final BigDecimal CENTER_LAT = new BigDecimal("33.558889");
    private static final BigDecimal CENTER_LNG = new BigDecimal("36.056944");

    private UUID nearest;      // ~25m from the center
    private UUID farther;      // ~309m from the center
    private UUID far;          // ~100km from the center — outside any legal radius
    private UUID noCoordinates; // null/null — never matches

    @BeforeEach
    void seed() {
        cacheManagerProvider.ifAvailable(cm -> cm.getCacheNames().forEach(name -> {
            var cache = cm.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }));

        propertyRepository.deleteAll();
        listingRepository.deleteAll();

        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_USER_ID, "p1-it@example.com", "p1-it@example.com", "P1 IT Provider");

        // CodeRabbit PR #300 round 1: the rows also vary DECLARED CAPACITY
        // (a=2, b=4, c/d undeclared) and PRICE (a=350, b=280) — the catalog
        // criteria guards below ride that variance; every pre-existing
        // assertion is criteria-less and stays byte-identical
        ProviderListing a = active("شقة قريبة جدًا", 2, 35000L);
        ProviderListing b = active("شقة في الحي المجاور", 4, 28000L);
        ProviderListing c = active("شقة بعيدة خارج النطاق");
        ProviderListing d = active("شقة بلا إحداثيات");
        listingRepository.saveAll(List.of(a, b, c, d));
        nearest = a.getId();
        farther = b.getId();
        far = c.getId();
        noCoordinates = d.getId();

        propertyRepository.save(PropertyDetails.create(nearest, PROVIDER_USER_ID,
                property(new BigDecimal("33.558700"), new BigDecimal("36.056800"), 70)));
        propertyRepository.save(PropertyDetails.create(farther, PROVIDER_USER_ID,
                property(new BigDecimal("33.560000"), new BigDecimal("36.060000"), 120)));
        propertyRepository.save(PropertyDetails.create(far, PROVIDER_USER_ID,
                property(new BigDecimal("34.000000"), new BigDecimal("37.000000"))));
        propertyRepository.save(PropertyDetails.create(noCoordinates, PROVIDER_USER_ID,
                property(null, null)));
    }

    private static PropertyDetailsRequest property(BigDecimal lat, BigDecimal lng, int areaM2) {
        return new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.APARTMENT,
                areaM2, 2, 1, null, null, null, null, null, null, null, lat, lng);
    }

    private static PropertyDetailsRequest property(BigDecimal lat, BigDecimal lng) {
        return property(lat, lng, 90);
    }

    private ProviderListing active(String title) {
        return active(title, null, 35000L);
    }

    private ProviderListing active(String title, Integer maxGuests, Long priceCents) {
        ProviderListing listing = ProviderListing.create(
                PROVIDER_USER_ID, title, title, "realestate", priceCents, "SAR", maxGuests);
        listing.activate();
        return listing;
    }

    private static SearchCriteria radius(String radiusKm) {
        return new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, CENTER_LAT, CENTER_LNG,
                new BigDecimal(radiusKm));
    }

    // ---- the plan's acceptance criteria -----------------------------------

    @Test
    void rowsWithoutCoordinates_neverMatchTheRadius() {
        // D (no coordinates) never appears — the partial-index predicate is
        // the query's predicate; the row cannot even be a candidate.
        Set<UUID> ids = filterPort.findListingIdsWithinRadius(CENTER_LAT, CENTER_LNG, 10_000L);

        assertThat(ids).containsExactlyInAnyOrder(nearest, farther);
        assertThat(ids).doesNotContain(noCoordinates);
    }

    @Test
    void nearRowsMatch_farRowOutsideTheRadius_doesNot() {
        Set<UUID> ids = filterPort.findListingIdsWithinRadius(CENTER_LAT, CENTER_LNG, 10_000L);

        assertThat(ids).contains(nearest, farther);
        assertThat(ids).doesNotContain(far);
    }

    @Test
    void aTightRadius_keepsOnlyTheClosestRow() {
        // 0.05 km = 50 m (whole meters) — only the ~25m row is inside.
        Set<UUID> ids = filterPort.findListingIdsWithinRadius(CENTER_LAT, CENTER_LNG, 50L);

        assertThat(ids).containsExactly(nearest);
    }

    @Test
    void partialRadiusPresence_is400BeforeAnyQuery() {
        // the type gate at construction — the stay window's group lesson
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, CENTER_LAT, null, null))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("provided together");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("10")))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("provided together");
    }

    @Test
    void sortDistance_ordersNearestFirst() {
        Page<ListingSummary> page = searchService.search(
                radius("10"), PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance")));

        // ~25m then ~309m — the native ORDER BY ST_Distance ASC with the
        // id tiebreak; the far and coordinate-less rows are absent
        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(nearest, farther);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void theServiceFlow_matchesThePortSet_forPlainRadiusSearches() {
        Page<ListingSummary> page = searchService.search(radius("10"), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactlyInAnyOrder(nearest, farther);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void anEmptyRadiusResult_isAnHonestEmptyPage() {
        // nothing within 1 m of the center
        Page<ListingSummary> page = searchService.search(radius("0.001"), PageRequest.of(0, 10));

        assertThat(page).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void distanceSort_withGuestsCriterion_excludesIneligibleListings() {
        // CodeRabbit PR #300 round 1 (thread 3): the catalog criteria resolve
        // BEFORE the radius pagination — the nearest row declares 2 guests,
        // so a guests=4 distance-sorted radius page carries the 4-guest row
        // alone (previously the criterion was silently ignored and BOTH
        // rows were returned)
        SearchCriteria withGuests = new SearchCriteria(null, null, null, null,
                null, null, 4, null, null, null, null, null, null,
                CENTER_LAT, CENTER_LNG, new BigDecimal("10"));
        Page<ListingSummary> page = searchService.search(withGuests,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance")));

        assertThat(page.getContent()).extracting(ListingSummary::id).containsExactly(farther);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    @Test
    void areaSort_withRadius_pagesThroughThePropertyOrdering_notA400() {
        // CodeRabbit PR #300 round 1 (thread 2): the area marker's radius
        // flow — formerly the set flow's second normalize() rejected the
        // controller-mapped marker+id pair as a "mixed marker" 400; the
        // flow did not exist. Now: the radius SET intersected with the
        // eligible ACTIVE set, the paged property form's area ordering (the
        // marker's direction honored), the coordinate-less row never a
        // candidate.
        Page<ListingSummary> page = searchService.search(radius("10"),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "area")));

        // area DESC: the 120 m2 row then the 70 m2 row — both within the
        // radius; total from the property side (the counts cannot lie)
        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(farther, nearest);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void priceSort_withRadius_isASortedPage_notA400() {
        // CodeRabbit PR #300 round 1 (thread 2 — normalize ONCE): the
        // controller-mapped sort (priceCents ASC + id) is consumed AS-IS by
        // the restricted Specification path — formerly rejected by the
        // service's second normalize() ("unsupported sort property:
        // priceCents") with a 400
        Page<ListingSummary> page = searchService.search(radius("10"),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "priceCents")
                        .and(Sort.by(Sort.Direction.ASC, "id"))));

        // 280 SAR (farther) before 350 SAR (nearest) — both within the
        // radius, ordered by the mapped price property
        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(farther, nearest);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void theGiSTIndexServesThePredicate_explainProvesIt() {
        // D-I3-aware form: tiny tables always justify a seq scan, so this
        // session disables seqscans to verify the index is USABLE (the
        // planner CAN and DOES choose it) — the honest guard against a
        // silently-broken index match. SET and EXPLAIN ride the SAME
        // connection (pool semantics).
        String plan = jdbcTemplate.execute((ConnectionCallback<String>) this::explainRadiusQuery);

        assertThat(plan).contains("idx_property_details_geog");
    }

    /**
     * Runs {@code SET enable_seqscan = off} + the EXPLAIN of the exact
     * predicate form the repository query uses, on this connection only.
     */
    private String explainRadiusQuery(Connection connection) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            String sql = """
                    EXPLAIN (FORMAT text)
                    SELECT listing_id FROM property_details
                    WHERE is_deleted = FALSE
                      AND latitude IS NOT NULL AND longitude IS NOT NULL
                      AND ST_DWithin(
                            ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
                            ST_SetSRID(ST_MakePoint(36.056944, 33.558889), 4326)::geography,
                            10000)
                    """;
            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = statement.executeQuery(sql)) {
                List<String> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString(1));
                }
                plan.append(String.join("\n", lines));
            } finally {
                statement.execute("SET enable_seqscan = on");
            }
            return plan.toString();
        }
    }
}
