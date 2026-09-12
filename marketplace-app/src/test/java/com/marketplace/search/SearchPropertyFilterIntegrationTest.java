package com.marketplace.search;

import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.realestate.PropertyDetails;
import com.marketplace.realestate.PropertyDetailsRepository;
import com.marketplace.realestate.PropertyDetailsRequest;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L32 integration on the REAL migration schema (the §7 lesson — isolated
 * postgres:18, Flyway enabled): the faceted search end-to-end — the geo
 * seed's Qudsayya tree (L30), property blocks written through the
 * realestate module (L31), and the search module's port composition
 * (this layer). Every L32 acceptance criterion that needs a live
 * database lives here.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SearchPropertyFilterIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private SearchService searchService;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private PropertyDetailsRepository propertyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    /** FK parent (V2: provider_listings.provider_id references users(id)). */
    private static final UUID PROVIDER_USER_ID = UUID.fromString("11111111-2222-4333-8444-555555555602");

    // the R__ seed's nodes (fixed UUIDs)
    private static final UUID QUDSAYYA = UUID.fromString("11111111-1111-4111-8111-111111111103");
    private static final UUID QUDSAYYA_SUBURB = UUID.fromString("11111111-1111-4111-8111-111111111105");
    private static final UUID AL_HAMAH = UUID.fromString("11111111-1111-4111-8111-111111111106");

    private UUID rentSmallQudsayya;
    private UUID saleBigSuburb;
    private UUID rentNoDetails;

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
                PROVIDER_USER_ID, "l32-it@example.com", "l32-it@example.com", "L32 IT Provider");

        ProviderListing small = active("شقة قدسيا صغيرة", "شقة غرفتين في البلد");
        ProviderListing big = active("فيلا ضاحية واسعة", "فيلا 300 متر مع حديقة");
        ProviderListing bare = active("شقة بلا تفاصيل", "شقة");
        listingRepository.saveAll(java.util.List.of(small, big, bare));
        rentSmallQudsayya = small.getId();
        saleBigSuburb = big.getId();
        rentNoDetails = bare.getId();

        propertyRepository.save(PropertyDetails.create(rentSmallQudsayya, PROVIDER_USER_ID,
                property(PropertyPurpose.RENT, PropertyType.APARTMENT, 90, 2, 1, QUDSAYYA)));
        propertyRepository.save(PropertyDetails.create(saleBigSuburb, PROVIDER_USER_ID,
                property(PropertyPurpose.SALE, PropertyType.VILLA, 300, 5, 3, QUDSAYYA_SUBURB)));
    }

    private static PropertyDetailsRequest property(PropertyPurpose purpose, PropertyType type,
                                                   int area, int rooms, int baths, UUID location) {
        return new PropertyDetailsRequest(purpose, type, area, rooms, baths,
                null, null, null, null, null, null, location, null, null);
    }

    private ProviderListing active(String title, String description) {
        ProviderListing listing = ProviderListing.create(
                PROVIDER_USER_ID, title, description, "realestate", 35000L, "SAR");
        listing.activate();
        return listing;
    }

    private static SearchCriteria facets(UUID locationId, PropertyPurpose purpose,
                                         Integer minRooms, Integer minAreaM2) {
        return new SearchCriteria(null, null, null, null, null, null, null,
                locationId, purpose, null, minRooms, null, minAreaM2);
    }

    @Test
    void locationFilter_qudsayya_returnsOnlyQudsayyaListings() {
        // Qudsayya covers the city + its three seeded neighborhoods — both
        // property-bearing listings match, the details-less one does not
        Page<ListingSummary> page = searchService.search(
                facets(QUDSAYYA, null, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactlyInAnyOrder(rentSmallQudsayya, saleBigSuburb);
    }

    @Test
    void locationFilter_aNeighborhood_returnsOnlyItsOwnListings() {
        Page<ListingSummary> page = searchService.search(
                facets(QUDSAYYA_SUBURB, null, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(saleBigSuburb);
    }

    @Test
    void locationFilter_unknownLocation_is404() {
        assertThatThrownBy(() -> searchService.search(
                facets(UUID.randomUUID(), null, null, null), PageRequest.of(0, 10)))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
    }

    @Test
    void purposeFilter_composesWithLocation() {
        Page<ListingSummary> page = searchService.search(
                facets(QUDSAYYA, PropertyPurpose.RENT, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(rentSmallQudsayya);
    }

    @Test
    void numericFacets_roomsAndArea() {
        Page<ListingSummary> byRooms = searchService.search(
                facets(null, null, 3, null), PageRequest.of(0, 10));
        assertThat(byRooms.getContent()).extracting(ListingSummary::id)
                .containsExactly(saleBigSuburb);

        Page<ListingSummary> byArea = searchService.search(
                facets(null, null, null, 100), PageRequest.of(0, 10));
        assertThat(byArea.getContent()).extracting(ListingSummary::id)
                .containsExactly(saleBigSuburb);
    }

    @Test
    void invalidCriterion_zeroMinRooms_is400BeforeAnyQuery() {
        assertThatThrownBy(() -> searchService.search(
                facets(null, null, 0, null), PageRequest.of(0, 10)))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("minRooms");
    }

    @Test
    void noMatchingProperty_isAnHonestEmptyPage() {
        Page<ListingSummary> page = searchService.search(
                facets(AL_HAMAH, null, null, null), PageRequest.of(0, 10));

        assertThat(page).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void areaSort_ordersByDeclaredArea_andCountsFromThePropertySide() {
        Page<ListingSummary> asc = searchService.search(
                facets(null, null, null, null), PageRequest.of(0, 10, Sort.by("area")));
        assertThat(asc.getContent()).extracting(ListingSummary::id)
                .containsExactly(rentSmallQudsayya, saleBigSuburb); // 90 then 300
        assertThat(asc.getTotalElements()).isEqualTo(2L);

        Page<ListingSummary> desc = searchService.search(
                facets(null, null, null, null), PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "area")));
        assertThat(desc.getContent()).extracting(ListingSummary::id)
                .containsExactly(saleBigSuburb, rentSmallQudsayya);
    }

    @Test
    void priceSort_onThePropertyFlow_ridesTheFacetedPath() {
        Page<ListingSummary> page = searchService.search(
                facets(QUDSAYYA, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "priceCents")));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactlyInAnyOrder(rentSmallQudsayya, saleBigSuburb);
    }

    @Test
    void textQuery_withPropertyCriteria_ranksAndFilters() {
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria("فيلا", null, null, null, null, null, null,
                        QUDSAYYA, PropertyPurpose.SALE, null, null, null, null),
                PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(saleBigSuburb);
    }

    @Test
    void legacyCriteria_stayUnmodified_noPropertyRoundTrip() {
        // no facets, no sort: the pre-L32 path — details-less listings are
        // ordinary results (the property layer is invisible)
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, "realestate", null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactlyInAnyOrder(rentSmallQudsayya, saleBigSuburb, rentNoDetails);
    }

    @Test
    void deterministicDefault_orderIsIdAscending() {
        Page<ListingSummary> page = searchService.search(
                facets(QUDSAYYA, null, null, null), PageRequest.of(0, 10));

        // the spec path's default = ORDER BY id ASC (byte-identical to the
        // native criteria path's order)
        assertThat(page.getContent()).extracting(ListingSummary::id)
                .isSortedAccordingTo(java.util.Comparator.<UUID>naturalOrder());
    }
}
