package com.marketplace.realestate;

import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * L31 module integration on the REAL migration schema (the §7 lesson —
 * isolated postgres:18 container, Flyway enabled, ddl-auto none, so the
 * JSONB amenities column, the V48 CHECKs and the UNIQUE constraint run
 * against exactly what the migrations produce). The seeding follows the
 * CatalogSearchFullTextIntegrationTest pattern (FK parent user first).
 *
 * <p>The geo tree port is mocked at the port level: the location gate is a
 * 404-contract test (the real tree is covered by L30's own integration
 * test; here only the port's contract matters).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PropertyModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private RealestateService realestateService;

    @Autowired
    private PropertyDetailsRepository propertyRepository;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private PropertyDetailsPort propertyDetailsPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void seedFkParentUser() {
        // The migration schema enforces provider_listings.provider_id ->
        // users(id) (V2): the parent row must exist before any listing.
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_USER_ID, "realestate-it@example.com",
                "realestate-it@example.com", "Realestate IT Provider");
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private GeoLookupPort geoLookupPort;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private CurrentUserProvider currentUserProvider;

    /** FK parent (V2: provider_listings.provider_id references users(id)). */
    private static final UUID PROVIDER_USER_ID = UUID.fromString("11111111-2222-4333-8444-555555555501");

    private final Authentication providerAuthentication = mock(Authentication.class);

    private ProviderListing activeListing() {
        return listingRepository.save(ProviderListing.create(
                PROVIDER_USER_ID, "شقة قدسيا " + UUID.randomUUID(), "وصف", "realestate",
                35000L, "SAR"));
    }

    private PropertyDetailsRequest request() {
        return new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.APARTMENT,
                120, 3, 2, 2, 5, 2015, true,
                List.of("elevator", "parking"), null, null, null, null);
    }

    private void asOwner() {
        org.mockito.Mockito.when(currentUserProvider.getCurrentUserId(providerAuthentication))
                .thenReturn(PROVIDER_USER_ID);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void upsert_thenPublicRead_roundTripsEveryField() {
        ProviderListing listing = activeListing();
        // The public read requires an ACTIVE listing — DRAFT -> ACTIVE is a
        // legal transition of the existing state machine.
        listing.activate();

        asOwner();
        var stored = realestateService.upsert(listing.getId(), request(), providerAuthentication);

        assertThat(stored.listingId()).isEqualTo(listing.getId());
        assertThat(stored.purpose()).isEqualTo(PropertyPurpose.RENT);
        assertThat(stored.propertyType()).isEqualTo(PropertyType.APARTMENT);
        assertThat(stored.amenities()).containsExactly("elevator", "parking");

        var publicView = realestateService.getPublicByListingId(listing.getId());
        assertThat(publicView).isEqualTo(stored);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void upsert_isIdempotent_oneBlockPerListing() {
        ProviderListing listing = activeListing();
        asOwner();

        realestateService.upsert(listing.getId(), request(), providerAuthentication);
        realestateService.upsert(listing.getId(),
                new PropertyDetailsRequest(PropertyPurpose.SALE, PropertyType.VILLA,
                        300, null, null, null, null, null, null, null, null, null, null, null),
                providerAuthentication);

        assertThat(propertyRepository.findByListingId(listing.getId())).isPresent();
        assertThat(propertyRepository.count()).isEqualTo(1L);
        assertThat(propertyRepository.findByListingId(listing.getId())
                .orElseThrow().getPurpose()).isEqualTo(PropertyPurpose.SALE);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void upsert_unknownLocation_is404BeforeAnyWrite() {
        ProviderListing listing = activeListing();
        UUID unknown = UUID.randomUUID();
        org.mockito.Mockito.when(geoLookupPort.getLocation(unknown))
                .thenThrow(new ResourceNotFoundException("GeoLocation", unknown));
        asOwner();

        assertThatThrownBy(() -> realestateService.upsert(listing.getId(),
                new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.APARTMENT,
                        null, null, null, null, null, null, null, null, null,
                        unknown, null, null), providerAuthentication))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(propertyRepository.findByListingId(listing.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void publicRead_inactiveListing_is404() {
        ProviderListing listing = activeListing(); // stays DRAFT — not ACTIVE

        assertThatThrownBy(() -> realestateService.getPublicByListingId(listing.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void publicRead_listingWithoutDetails_is404() {
        ProviderListing listing = activeListing();
        listing.activate();

        assertThatThrownBy(() -> realestateService.getPublicByListingId(listing.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void invalidValues_neverReachTheDatabase() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), PROVIDER_USER_ID,
                new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.LAND,
                        -10, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThat(propertyRepository.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void envers_everyModificationLeavesARevision() {
        ProviderListing listing = activeListing();
        asOwner();

        realestateService.upsert(listing.getId(), request(), providerAuthentication);
        PropertyDetails stored = propertyRepository.findByListingId(listing.getId()).orElseThrow();
        var revisions = propertyRepository.findRevisions(stored.getId(),
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(revisions.getContent()).isNotEmpty();

        realestateService.upsert(listing.getId(),
                new PropertyDetailsRequest(PropertyPurpose.SALE, PropertyType.VILLA,
                        null, null, null, null, null, null, null, null, null, null, null, null),
                providerAuthentication);
        var afterUpdate = propertyRepository.findRevisions(stored.getId(),
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(afterUpdate.getContent()).hasSize(revisions.getContent().size() + 1);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void uniqueListingIdConstraint_twoBlocksForOneListingAreImpossible() {
        ProviderListing listing = activeListing();

        assertThatThrownBy(() -> propertyRepository.saveAndFlush(
                PropertyDetails.create(listing.getId(), PROVIDER_USER_ID, request())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> propertyRepository.saveAndFlush(
                PropertyDetails.create(listing.getId(), PROVIDER_USER_ID, request())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraints_backTheEntityFloorForRawWriters() {
        PropertyDetails direct = propertyRepository.saveAndFlush(
                PropertyDetails.create(UUID.randomUUID(), PROVIDER_USER_ID, request()));

        // A raw SQL writer that bypasses the entity floor is still rejected
        // by the V48 CHECK (PostgreSQL SQLSTATE 23514, check_violation).
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                entityManager.createNativeQuery(
                                "UPDATE property_details SET area_m2 = -1 WHERE id = :id")
                        .setParameter("id", direct.getId())
                        .executeUpdate());

        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(root.getMessage()).contains("chk_property_details_area_positive");
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void findByListingIds_servesTheCatalogBatchEmbed() {
        ProviderListing withDetails = activeListing();
        ProviderListing withoutDetails = activeListing();
        asOwner();
        realestateService.upsert(withDetails.getId(), request(), providerAuthentication);

        Map<UUID, PropertyDetailsPort.PropertyView> map = propertyDetailsPort
                .findByListingIds(Set.of(withDetails.getId(), withoutDetails.getId()));

        assertThat(map).containsKey(withDetails.getId())
                .doesNotContainKey(withoutDetails.getId());

        Optional<PropertyDetailsPort.PropertyView> single = propertyDetailsPort
                .findByListingId(withDetails.getId());
        assertThat(single).isPresent();
        assertThat(single.orElseThrow().purpose()).isEqualTo(PropertyPurpose.RENT);
    }

    @Test
    void coordinatesAndAmenities_persistAsNativeTypes() {
        PropertyDetails withCoordinates = PropertyDetails.create(
                UUID.randomUUID(), PROVIDER_USER_ID,
                new PropertyDetailsRequest(PropertyPurpose.SALE, PropertyType.VILLA,
                        null, null, null, null, null, null, null,
                        List.of("pool", "garage"), null, null,
                        BigDecimal.valueOf(33.5138), BigDecimal.valueOf(36.2765)));
        PropertyDetails stored = propertyRepository.saveAndFlush(withCoordinates);

        assertThat(stored.getLatitude()).isEqualByComparingTo("33.5138");
        assertThat(stored.getLongitude()).isEqualByComparingTo("36.2765");
        assertThat(stored.getAmenities()).containsExactly("pool", "garage");
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void ownership_foreignProvider_is403() {
        ProviderListing listing = activeListing();
        org.mockito.Mockito.when(currentUserProvider.getCurrentUserId(providerAuthentication))
                .thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> realestateService.upsert(listing.getId(), request(),
                providerAuthentication))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(propertyRepository.findByListingId(listing.getId())).isEmpty();
    }
}
