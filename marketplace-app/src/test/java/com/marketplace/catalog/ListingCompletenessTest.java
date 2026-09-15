package com.marketplace.catalog;

import com.marketplace.shared.api.PropertyDetailsPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L38 (realestate systems plan §5): the completeness equation's boundary
 * matrix — the plan's acceptance criteria 1 and 2 measured against the
 * DOCUMENTED equation (equal quarters over the plan's four component
 * groups). A pure value-object test: no Spring, no database — the record's
 * static factory is the single source of the equation, so these are the
 * exact numbers the service, the controller and the HTTP surface all
 * inherit.
 */
class ListingCompletenessTest {

    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID LISTING = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    /** Criterion 1 — every group present: 100 and all four flags true. */
    @Test
    void allGroupsPresent_answers100() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 1, property(LOCATION));

        assertThat(score.percent()).isEqualTo(100);
        assertThat(score.coreFieldsPresent()).isTrue();
        assertThat(score.photosPresent()).isTrue();
        assertThat(score.propertyDetailsPresent()).isTrue();
        assertThat(score.locationPresent()).isTrue();
    }

    /**
     * Criterion 2 — the plan's own example: a listing with the core fields
     * but NO photos, NO property details, NO location answers the LOW
     * quarter score 25 (three of the four groups missing).
     */
    @Test
    void coreOnly_noPhotosDetailsLocation_answers25() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 0, null);

        assertThat(score.percent()).isEqualTo(25);
        assertThat(score.coreFieldsPresent()).isTrue();
        assertThat(score.photosPresent()).isFalse();
        assertThat(score.propertyDetailsPresent()).isFalse();
        assertThat(score.locationPresent()).isFalse();
    }

    /** Nothing but the schema-mandatory title/price: the floor score 0. */
    @Test
    void nothingButMandatoryFields_answers0() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", null, 1000L), 0, null);

        assertThat(score.percent()).isZero();
        assertThat(score.coreFieldsPresent()).isFalse();
        assertThat(score.photosPresent()).isFalse();
        assertThat(score.propertyDetailsPresent()).isFalse();
        assertThat(score.locationPresent()).isFalse();
    }

    /** A blank description is a missing description (trimmed honesty). */
    @Test
    void blankDescription_dropsOnlyTheCoreQuarter() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "   ", 1000L), 1, property(LOCATION));

        assertThat(score.percent()).isEqualTo(75);
        assertThat(score.coreFieldsPresent()).isFalse();
        assertThat(score.photosPresent()).isTrue();
        assertThat(score.propertyDetailsPresent()).isTrue();
        assertThat(score.locationPresent()).isTrue();
    }

    /** No photo: only the photo quarter drops (three groups stay). */
    @Test
    void noPhotos_dropsOnlyThePhotoQuarter() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 0, property(LOCATION));

        assertThat(score.percent()).isEqualTo(75);
        assertThat(score.photosPresent()).isFalse();
    }

    /** Only >= 1 matters: three photos earn the same quarter as one. */
    @Test
    void threePhotos_earnTheSameQuarterAsOne() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 3, property(LOCATION));

        assertThat(score.percent()).isEqualTo(100);
    }

    /**
     * No property block: BOTH the details and the location quarters drop —
     * the L30 location lives inside the L31 block (location_id is its
     * column), so a missing block means both criteria are unmet.
     */
    @Test
    void noPropertyBlock_dropsDetailsAndLocationQuarters() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 1, null);

        assertThat(score.percent()).isEqualTo(50);
        assertThat(score.propertyDetailsPresent()).isFalse();
        assertThat(score.locationPresent()).isFalse();
    }

    /**
     * A property block WITHOUT a location: the details quarter is earned,
     * the location quarter is not — the L30 criterion is the administrative
     * node attachment (location_id), which the block itself marks absent
     * with a null.
     */
    @Test
    void propertyWithoutLocation_earnsDetailsButNotLocation() {
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 1, property(null));

        assertThat(score.percent()).isEqualTo(75);
        assertThat(score.propertyDetailsPresent()).isTrue();
        assertThat(score.locationPresent()).isFalse();
    }

    /**
     * The L30 criterion is the administrative node, NOT the display
     * coordinates: a block carrying lat/lng but no location_id still misses
     * the location quarter (the plan's own distinction).
     */
    @Test
    void displayCoordinatesDoNotEarnTheLocationQuarter() {
        var property = new PropertyDetailsPort.PropertyView(
                LISTING, null, null, null, null, null, null, null, null,
                null, List.of(), null, null, BigDecimal.ONE, BigDecimal.ONE);
        var score = ListingCompletenessResponse.of(
                listing("Villa", "sea view", 1000L), 1, property);

        assertThat(score.locationPresent()).isFalse();
        assertThat(score.percent()).isEqualTo(75);
    }

    // ---- fixtures -------------------------------------------------------------

    private static ProviderListing listing(String title, String description, Long priceCents) {
        return ProviderListing.create(PROVIDER, title, description, "APARTMENT", priceCents);
    }

    private static PropertyDetailsPort.PropertyView property(UUID locationId) {
        return new PropertyDetailsPort.PropertyView(
                LISTING,
                com.marketplace.shared.api.PropertyPurpose.RENT,
                com.marketplace.shared.api.PropertyType.APARTMENT,
                120, 3, 2, 1, 4, 2015, true,
                List.of("elevator", "parking"), LocalDate.of(2026, 10, 1),
                locationId, null, null);
    }
}
