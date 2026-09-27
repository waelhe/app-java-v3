package com.marketplace.catalog;

import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L39 (realestate systems plan §5 — SEO): the JSON-LD block's unit
 * guards — the schema.org {@code RealEstateListing} comparative field
 * test (the plan's criterion 3: every emitted field is a measured
 * schema.org fact — the GoodRelations
 * businessFunction values the standard adopted, the PostalAddress
 * level mapping) and the honesty rules (block only for real-estate
 * listings, url only when the public origin is bound, address only from
 * the resolved L30 chain).
 */
class ListingSeoServiceTest {

    private static final String BASE = "https://public.example";

    private static final UUID COUNTRY_ID = UUID.randomUUID();
    private static final UUID GOVERNORATE_ID = UUID.randomUUID();
    private static final UUID CITY_ID = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    private GeoLookupPort geoLookupPort;
    private ListingSeoService service;

    @BeforeEach
    void setUp() {
        geoLookupPort = mock(GeoLookupPort.class);
        service = new ListingSeoService(geoLookupPort, properties(BASE));
    }

    @Test
    void jsonLdFor_listingWithoutPropertyBlock_isEmpty_notARealEstateListing() {
        assertThat(service.jsonLdFor(listing(null))).isEmpty();
    }

    @Test
    void jsonLdFor_fullMapping_everyFieldIsAMeasuredSchemaOrgFact() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        var block = service.jsonLdFor(listing(property(NEIGHBORHOOD_ID, PropertyPurpose.RENT))).orElseThrow();

        assertThat(block.context()).isEqualTo("https://schema.org");
        assertThat(block.type()).isEqualTo("RealEstateListing");
        assertThat(block.name()).isEqualTo("شقة قدسيا للإيجار");
        assertThat(block.description()).isEqualTo("وصف الشقة");
        assertThat(block.url()).isEqualTo(BASE + "/listings/00000000-0000-0000-0000-000000000001");
        // datePosted is deliberately ABSENT (CodeRabbit round 1 adoption):
        // the entity records no publication instant — createdAt is the
        // draft's date, and fabricating the field misleads consumers.
        assertThat(block.dateModified()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        // The Offer: the response's own price/currency + the GoodRelations
        // businessFunction value (schema.org/BusinessFunction's members).
        assertThat(block.offers().type()).isEqualTo("Offer");
        assertThat(block.offers().price()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(block.offers().priceCurrency()).isEqualTo("SAR");
        assertThat(block.offers().businessFunction())
                .isEqualTo("http://purl.org/goodrelations/v1#LeaseOut");
        // The PostalAddress: the L30 chain level-mapped to the standard's
        // three place fields (0=country, 1=region, 2=locality).
        assertThat(block.address().type()).isEqualTo("PostalAddress");
        assertThat(block.address().addressCountry()).isEqualTo("فلسطين");
        assertThat(block.address().addressRegion()).isEqualTo("محافظة القدس");
        assertThat(block.address().addressLocality()).isEqualTo("القدس");
    }

    @Test
    void jsonLdFor_salePurpose_mapsToTheSellBusinessFunction() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        var block = service.jsonLdFor(listing(property(NEIGHBORHOOD_ID, PropertyPurpose.SALE))).orElseThrow();

        assertThat(block.offers().businessFunction())
                .isEqualTo("http://purl.org/goodrelations/v1#Sell");
    }

    /**
     * The neighborhood level carries no PostalAddress field in the
     * standard (streetAddress/postOfficeBoxNumber/addressLocality/
     * addressRegion/postalCode/addressCountry) — it is never invented
     * into the structured block; the city stays the locality.
     */
    @Test
    void jsonLdFor_neighborhoodAttached_theCityRemainsTheLocality() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        var block = service.jsonLdFor(listing(property(NEIGHBORHOOD_ID, PropertyPurpose.RENT))).orElseThrow();

        assertThat(block.address().addressLocality()).isEqualTo("القدس");
        assertThat(block.address().addressRegion()).isEqualTo("محافظة القدس");
    }

    @Test
    void jsonLdFor_cityAttached_omitsNothingBelowItsOwnLevel() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        var block = service.jsonLdFor(listing(property(CITY_ID, PropertyPurpose.RENT))).orElseThrow();

        assertThat(block.address().addressLocality()).isEqualTo("القدس");
        assertThat(block.address().addressRegion()).isEqualTo("محافظة القدس");
        assertThat(block.address().addressCountry()).isEqualTo("فلسطين");
    }

    @Test
    void jsonLdFor_propertyWithoutLocation_omitsTheAddressNeverInventsOne() {
        var block = service.jsonLdFor(listing(property(null, PropertyPurpose.RENT))).orElseThrow();

        assertThat(block.address()).isNull();
        assertThat(block.offers()).isNotNull();
    }

    @Test
    void jsonLdFor_locationAbsentFromTheTree_omitsTheAddress() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        var block = service.jsonLdFor(listing(property(UUID.randomUUID(), PropertyPurpose.RENT))).orElseThrow();

        assertThat(block.address()).isNull();
    }

    @Test
    void jsonLdFor_capabilityOff_omitsTheUrlNeverFabricatesOne() {
        service = new ListingSeoService(geoLookupPort, properties(""));
        when(geoLookupPort.getTree()).thenReturn(tree());

        var block = service.jsonLdFor(listing(property(NEIGHBORHOOD_ID, PropertyPurpose.RENT))).orElseThrow();

        assertThat(block.url()).isNull();
        assertThat(block.name()).isNotNull();
    }

    // ---- the chain resolution (one cached port call) ----

    @Test
    void addressChain_resolvesAttachedNodeFirstUpToTheRoot() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        List<GeoNode> chain = service.addressChain(NEIGHBORHOOD_ID);

        assertThat(chain).extracting(GeoNode::level).containsExactly(3, 2, 1, 0);
    }

    @Test
    void addressChain_nullLocation_isEmpty() {
        assertThat(service.addressChain(null)).isEmpty();
    }

    @Test
    void addressChain_unknownNode_isEmpty() {
        when(geoLookupPort.getTree()).thenReturn(tree());

        assertThat(service.addressChain(UUID.randomUUID())).isEmpty();
    }

    // ---- helpers ----

    private static CatalogProperties properties(String base) {
        return new CatalogProperties(null,
                new CatalogProperties.Seo(base, "/listings/{id}", List.of()),
                new CatalogProperties.Views("test-key", java.time.Duration.ofDays(1)));
    }

    /** فلسطين → محافظة القدس → القدس → بيت حنينا (the seeded shape). */
    private static GeoNode tree() {
        GeoNode country = new GeoNode(COUNTRY_ID, null, 0, "فلسطين", "Palestine", "palestine");
        GeoNode governorate = new GeoNode(GOVERNORATE_ID, COUNTRY_ID, 1, "محافظة القدس", "Jerusalem Governorate", "jerusalem-gov");
        GeoNode city = new GeoNode(CITY_ID, GOVERNORATE_ID, 2, "القدس", "Jerusalem", "jerusalem");
        GeoNode neighborhood = new GeoNode(NEIGHBORHOOD_ID, CITY_ID, 3, "بيت حنينا", "Beit Hanina", "beit-hanina");
        return new GeoNode(country.id(), null, 0, country.nameAr(), country.nameEn(), country.slug(),
                List.of(new GeoNode(governorate.id(), governorate.parentId(), 1, governorate.nameAr(),
                        governorate.nameEn(), governorate.slug(),
                        List.of(new GeoNode(city.id(), city.parentId(), 2, city.nameAr(), city.nameEn(),
                                city.slug(),
                                List.of(neighborhood))))));
    }

    private static PropertyView property(UUID locationId, PropertyPurpose purpose) {
        return new PropertyView(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                purpose, PropertyType.APARTMENT, 90, 3, 2, 2, 4, 2015, true,
                List.of("مصعد"), null, locationId, null, null);
    }

    private static ListingResponse listing(PropertyView propertyView) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new ListingResponse(id, "شقة قدسيا للإيجار", "وصف الشقة", "stay",
                new BigDecimal("350.00"), "SAR", 4,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"))
                .withProperty(propertyView);
    }
}
