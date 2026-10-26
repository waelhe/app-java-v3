package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The {@code SearchCriteria} record's own gate law — every construction
 * gate and every presence probe, one test per branch: the stay-window
 * invariant (incomplete and non-positive windows), the positive-or-absent
 * numeric criteria, the price-bound law (zero valid, negative and
 * inverted ranges rejected), the radius triple (all-or-none, coordinate
 * ranges, the (0, 50] radius cap, the meter granularity, the scale-6
 * center normalization), the min-rating floor, the blank-category
 * normalization, and the legacy construction forms.
 */
class SearchCriteriaTest {

    private final Instant checkIn = Instant.parse("2026-10-01T14:00:00Z");
    private final Instant checkOut = Instant.parse("2026-10-05T10:00:00Z");

    // ------------------------------------------------------------------
    // the stay-window invariant
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsAnIncompleteWindow() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                checkIn, null, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("checkIn and checkOut must be provided together");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, checkOut, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("checkIn and checkOut must be provided together");
    }

    @Test
    void constructor_rejectsANonPositiveWindow() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                checkIn, checkIn, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("checkIn must be strictly before checkOut");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                checkOut, checkIn, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("checkIn must be strictly before checkOut");
    }

    @Test
    void constructor_acceptsAValidHalfOpenWindow() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                checkIn, checkOut, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(criteria.hasWindow()).isTrue();
        assertThat(criteria.checkIn()).isEqualTo(checkIn);
        assertThat(criteria.checkOut()).isEqualTo(checkOut);
    }

    // ------------------------------------------------------------------
    // the positive-or-absent numeric criteria
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsNonPositiveOccupancyCriteria() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, 0, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("guests must be positive");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, 0, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("minRooms must be positive");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, -1, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("minBathrooms must be positive");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, 0, null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("minAreaM2 must be positive");
    }

    @Test
    void constructor_acceptsPositiveOccupancyCriteria() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, 4, null, null, null, 2, 1, 80, null, null, null, null);
        assertThat(criteria.guests()).isEqualTo(4);
        assertThat(criteria.hasCatalogCriteria()).isTrue();
        assertThat(criteria.hasPropertyCriteria()).isTrue();
    }

    // ------------------------------------------------------------------
    // the price-bound law (W6)
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsNegativeAndInvertedPriceBounds() {
        assertThatThrownBy(() -> new SearchCriteria(null, null,
                BigDecimal.valueOf(-1), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("minPrice must be non-negative");
        assertThatThrownBy(() -> new SearchCriteria(null, null,
                null, BigDecimal.valueOf(-0.01), null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("maxPrice must be non-negative");
        assertThatThrownBy(() -> new SearchCriteria(null, null,
                BigDecimal.TEN, BigDecimal.ONE, null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("minPrice must be less than or equal to maxPrice");
    }

    /**
     * Row 3 (agent findings 2026-10-26 14:33 export, CWE-755): the shared
     * price-filter ceiling — the catalogue consumer's own supported
     * monetary range (100000000 major units), enforced at construction so
     * a stored saved search can never carry a criterion its eventual
     * query consumer would reject at scan time (the deterministic abort
     * of the population-wide alert transaction).
     */
    @Test
    void constructor_rejectsPricesBeyondTheSupportedMonetaryRange() {
        assertThatThrownBy(() -> new SearchCriteria(null, null,
                new BigDecimal("100000001"), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("minPrice is limited to the supported monetary range: 0 .. 100000000 (major units)");
        assertThatThrownBy(() -> new SearchCriteria(null, null,
                null, new BigDecimal("100000000.01"), null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("maxPrice is limited to the supported monetary range: 0 .. 100000000 (major units)");
        // The boundary itself is legal — the consumer's own inclusive cap.
        SearchCriteria atTheCap = new SearchCriteria(null, null,
                new BigDecimal("100000000"), new BigDecimal("100000000"), null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(atTheCap.minPrice()).isEqualByComparingTo(new BigDecimal("100000000"));
    }

    @Test
    void constructor_acceptsTheZeroFloorAndTheExactFreeMatch() {
        // Zero is VALID on both bounds — the free-floor filter [0, max]
        // and the exact free-listing match [0, 0].
        SearchCriteria floor = new SearchCriteria(null, null,
                BigDecimal.ZERO, BigDecimal.valueOf(250), null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(floor.minPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        SearchCriteria free = new SearchCriteria(null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(free.hasCatalogCriteria()).isTrue();
    }

    // ------------------------------------------------------------------
    // the radius triple (P1)
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsAPartialRadiusTriple() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                BigDecimal.ONE, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("latitude, longitude and radiusKm must be provided together");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                BigDecimal.ONE, BigDecimal.ONE, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("latitude, longitude and radiusKm must be provided together");
    }

    @Test
    void constructor_rejectsOutOfRangeCoordinatesAndRadius() {
        assertRadiusRejected(BigDecimal.valueOf(-90.0000001), BigDecimal.ZERO, BigDecimal.ONE,
                "latitude must be within [-90, 90]");
        assertRadiusRejected(BigDecimal.valueOf(90.0000001), BigDecimal.ZERO, BigDecimal.ONE,
                "latitude must be within [-90, 90]");
        assertRadiusRejected(BigDecimal.ZERO, BigDecimal.valueOf(-180.0000001), BigDecimal.ONE,
                "longitude must be within [-180, 180]");
        assertRadiusRejected(BigDecimal.ZERO, BigDecimal.valueOf(180.0000001), BigDecimal.ONE,
                "longitude must be within [-180, 180]");
        assertRadiusRejected(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "radiusKm must be within (0, 50]");
        assertRadiusRejected(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(50.001),
                "radiusKm must be within (0, 50]");
    }

    @Test
    void constructor_rejectsSubMeterRadiusPrecision() {
        assertRadiusRejected(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.0005"),
                "radiusKm precision is the meter (at most 3 decimal places)");
    }

    @Test
    void constructor_rejectsExponentAmplifiedScalesBeforeAnyDecimalArithmetic() {
        // Row 1 (agent findings 2026-10-26 — CWE-400): the finding's own
        // payload shape — a SHORT exponent form whose magnitude passes
        // every comparison while its scale would make setScale construct
        // an enormous power of ten. The representation bound answers the
        // honest 400 for every component of the triple, and a generous
        // trailing-zero form stays legal (the vocabulary stores six).
        BigDecimal amplified = new BigDecimal("1e-100000000");
        assertThat(amplified.compareTo(BigDecimal.valueOf(90))).isLessThan(0);
        assertRadiusRejected(amplified, BigDecimal.ZERO, BigDecimal.ONE,
                "latitude carries more than 32 decimal places — the coordinate vocabulary stores six");
        assertRadiusRejected(BigDecimal.ZERO, amplified, BigDecimal.ONE,
                "longitude carries more than 32 decimal places — the coordinate vocabulary stores six");
        assertRadiusRejected(BigDecimal.ZERO, BigDecimal.ZERO, amplified,
                "radiusKm carries more than 32 decimal places — the coordinate vocabulary stores six");
    }

    @Test
    void constructor_acceptsGenerousTrailingZeroCoordinates() {
        // The bound's honest half: a coordinate padded well past the stored
        // six decimals is a legal representation — the bound cuts the
        // exponent-amplification axis, never the trailing-zero vocabulary.
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("24.713600000000000000"), new BigDecimal("46.675500000000000000"),
                new BigDecimal("12.345"), null);
        assertThat(criteria.hasRadius()).isTrue();
        assertThat(criteria.latitude()).isEqualByComparingTo(new BigDecimal("24.7136"));
    }

    @Test
    void constructor_acceptsTheRadiusTripleAndNormalizesTheCenterToScaleSix() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("24.7136552"), new BigDecimal("46.6755267"),
                new BigDecimal("12.345"), null);
        assertThat(criteria.hasRadius()).isTrue();
        // D-P12: the center is normalized to the stored NUMERIC(9,6) scale —
        // the query and the cache key see the identical value.
        assertThat(criteria.latitude()).isEqualByComparingTo(new BigDecimal("24.713655"));
        assertThat(criteria.longitude()).isEqualByComparingTo(new BigDecimal("46.675527"));
        assertThat(criteria.radiusMeters()).isEqualTo(12345L);
    }

    @Test
    void constructor_acceptsTheExactRadiusCap() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(50), null);
        assertThat(criteria.radiusMeters()).isEqualTo(50_000L);
    }

    private static void assertRadiusRejected(BigDecimal latitude, BigDecimal longitude,
                                             BigDecimal radiusKm, String expectedMessage) {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                latitude, longitude, radiusKm, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(expectedMessage);
    }

    // ------------------------------------------------------------------
    // the min-rating floor (W3)
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsAMinRatingOutsideTheStoredBounds() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.ZERO))
                .isInstanceOf(BadRequestException.class).hasMessage("minRating must be within [1, 5]");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.valueOf(5.01)))
                .isInstanceOf(BadRequestException.class).hasMessage("minRating must be within [1, 5]");
    }

    @Test
    void constructor_acceptsTheRatingFloorBounds() {
        SearchCriteria one = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.ONE);
        assertThat(one.hasMinRating()).isTrue();
        SearchCriteria five = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, BigDecimal.valueOf(5));
        assertThat(five.hasMinRating()).isTrue();
    }

    // ------------------------------------------------------------------
    // the blank-category normalization (R6) and the presence probes
    // ------------------------------------------------------------------

    @Test
    void constructor_normalizesABlankCategoryToTheCriterionLessForm() {
        SearchCriteria blank = new SearchCriteria("q", "  ", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(blank.category()).isNull();
        assertThat(blank.hasCatalogCriteria()).isFalse();
    }

    @Test
    void theLegacyFormsCompileAndCarryNoCriteria() {
        SearchCriteria four = new SearchCriteria("q", "cat", null, null);
        assertThat(four.hasWindow()).isFalse();
        assertThat(four.hasPropertyCriteria()).isFalse();
        assertThat(four.hasCatalogCriteria()).isTrue();

        SearchCriteria six = new SearchCriteria("q", "cat", null, null, checkIn, checkOut);
        assertThat(six.hasWindow()).isTrue();

        SearchCriteria seven = new SearchCriteria("q", "cat", null, null, checkIn, checkOut, 2);
        assertThat(seven.guests()).isEqualTo(2);
        assertThat(seven.hasCatalogCriteria()).isTrue();
    }

    @Test
    void theFacetPresenceProbesAnswerTheirOwnCompositions() {
        UUID locationId = UUID.randomUUID();
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, locationId, PropertyPurpose.RENT, PropertyType.VILLA,
                null, null, null, null, null, null, null);
        assertThat(criteria.hasPropertyCriteria()).isTrue();
        assertThat(criteria.purpose()).isEqualTo(PropertyPurpose.RENT);
        assertThat(criteria.propertyType()).isEqualTo(PropertyType.VILLA);
        assertThat(criteria.locationId()).isEqualTo(locationId);

        SearchCriteria empty = new SearchCriteria("q", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(empty.hasPropertyCriteria()).isFalse();
        assertThat(empty.hasCatalogCriteria()).isFalse();
        assertThat(empty.hasRadius()).isFalse();
        assertThat(empty.hasMinRating()).isFalse();
        assertThat(empty.hasWindow()).isFalse();
    }
}
