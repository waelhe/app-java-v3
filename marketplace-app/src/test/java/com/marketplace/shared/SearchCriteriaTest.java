package com.marketplace.shared;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L27 (feature-expansion roadmap §5): the SearchCriteria record IS the
 * input gate for the stay window — acceptance criterion 0 ("validate the
 * inputs before the predicate: both dates together and a strictly positive
 * window — a request with one date or an empty/reversed/equal window is a
 * 400 before any query"). The invariant lives in the canonical constructor:
 * an invalid window cannot be constructed, by any caller, so no downstream
 * code ever sees one. {@code BadRequestException} maps to HTTP 400 through
 * the shared {@code GlobalExceptionHandler}.
 *
 * <p>P1 (postgis integration plan §D-P6): the same discipline for the
 * radius triple — every construction path (the canonical 16-component form
 * plus the three legacy convenience forms) is exercised: the triple is
 * present together or absent; the ranges mirror V48; the radius ceiling is
 * the plan's (0, 50] calibration with whole-meter granularity; the center
 * is normalized to the stored coordinate scale.
 */
class SearchCriteriaTest {

    private static final Instant CHECK_IN = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant CHECK_OUT = Instant.parse("2026-09-28T10:00:00Z");

    @Test
    void legacyFourComponentForm_isAWindowlessCriteria() {
        SearchCriteria criteria = new SearchCriteria("q", "cat", BigDecimal.ONE, BigDecimal.TEN);

        assertThat(criteria.hasWindow()).isFalse();
        assertThat(criteria.checkIn()).isNull();
        assertThat(criteria.checkOut()).isNull();
    }

    @Test
    void bothNullDates_isAWindowlessCriteria() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null);

        assertThat(criteria.hasWindow()).isFalse();
    }

    @Test
    void validWindow_isAccepted() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT);

        assertThat(criteria.hasWindow()).isTrue();
        assertThat(criteria.checkIn()).isEqualTo(CHECK_IN);
        assertThat(criteria.checkOut()).isEqualTo(CHECK_OUT);
    }

    @Test
    void oneDateOnly_isRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, CHECK_IN, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, null, CHECK_OUT))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void reversedWindow_isRejected() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, CHECK_OUT, CHECK_IN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("strictly before");
    }

    @Test
    void equalWindow_isRejected() {
        // The [checkIn, checkOut) convention with an exclusive end makes a
        // zero-length window meaningless — a 400, not a silently-empty page.
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_IN))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- I6: the guests criterion gate (criterion 0) -------------------------

    @Test
    void nullGuests_isTheCriterionLessForm() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, null);

        assertThat(criteria.guests()).isNull();
        assertThat(criteria.hasWindow()).isFalse();
    }

    @Test
    void positiveGuests_isAccepted() {
        SearchCriteria criteria = new SearchCriteria("loft", "stay", null, null, null, null, 4);

        assertThat(criteria.guests()).isEqualTo(4);
    }

    @Test
    void zeroGuests_isRejectedBeforeAnyQuery() {
        // A guest count of zero is meaningless — a 400 at construction,
        // never a silently-empty page (the same lesson as the window).
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, null, null, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("guests must be positive");
    }

    @Test
    void negativeGuests_isRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> new SearchCriteria("loft", null, null, null, null, null, -2))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("guests must be positive");
    }

    @Test
    void guestsGate_isIndependentOfTheWindowGate() {
        // A valid window does NOT smuggle an invalid guests value past the
        // gate — and vice versa (an invalid window still fails even when
        // guests is fine).
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("guests must be positive");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null, CHECK_IN, null, 3))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("provided together");
    }

    @Test
    void windowAndPositiveGuests_compose() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT, 2);

        assertThat(criteria.hasWindow()).isTrue();
        assertThat(criteria.guests()).isEqualTo(2);
    }

    @Test
    void sixComponentForm_keepsGuestsNull() {
        // The L27-era construction sites compile unchanged — guests stays
        // criterion-less.
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT);

        assertThat(criteria.guests()).isNull();
    }

    // ---- L32: the real-estate facet gates -------------------------------

    @Test
    void facetNumericCriteria_zeroOrNegative_isRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, 0, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minRooms");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, -1, null, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBathrooms");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, -50, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minAreaM2");
    }

    @Test
    void facetCriteria_positiveValues_constructCleanly() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, java.util.UUID.randomUUID(),
                com.marketplace.shared.api.PropertyPurpose.RENT,
                com.marketplace.shared.api.PropertyType.APARTMENT, 2, 1, 80, null, null, null);

        assertThat(criteria.minRooms()).isEqualTo(2);
        assertThat(criteria.minBathrooms()).isEqualTo(1);
        assertThat(criteria.minAreaM2()).isEqualTo(80);
        assertThat(criteria.hasPropertyCriteria()).isTrue();
    }

    @Test
    void legacyForms_haveNoPropertyCriteria_andBehaviorIsUnchanged() {
        assertThat(new SearchCriteria("q", "cat", null, null).hasPropertyCriteria()).isFalse();
        assertThat(new SearchCriteria(null, null, null, null, null, null).hasPropertyCriteria()).isFalse();
        assertThat(new SearchCriteria(null, null, null, null, null, null, null).hasPropertyCriteria()).isFalse();
        // the canonical 16-arg form with all facets and no radius is the
        // legacy form too
        assertThat(new SearchCriteria("q", "cat", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).hasPropertyCriteria()).isFalse();
        assertThat(new SearchCriteria("q", "cat", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).hasRadius()).isFalse();
    }

    // ---- P1 (postgis plan §D-P6): the radius triple gates ----------------

    private static final BigDecimal LAT = new BigDecimal("33.558889");
    private static final BigDecimal LNG = new BigDecimal("36.056944");

    @Test
    void validRadiusTriple_isAcceptedAndHasRadius() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));

        assertThat(criteria.hasRadius()).isTrue();
        assertThat(criteria.radiusMeters()).isEqualTo(10_000L);
        assertThat(criteria.latitude().compareTo(LAT)).isZero();
        assertThat(criteria.longitude().compareTo(LNG)).isZero();
    }

    @Test
    void radiusTriple_withFractionalKmInWholeMeters_isAccepted() {
        // 0.25 km = 250 m — whole meters, the documented granularity.
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("0.25"));

        assertThat(criteria.radiusMeters()).isEqualTo(250L);
    }

    @Test
    void partialRadiusPresence_isRejectedBeforeAnyQuery() {
        // one coordinate without the other...
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("provided together");
        // ...the radius without a center...
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, BigDecimal.TEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("provided together");
        // ...and two of the three.
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("provided together");
    }

    @Test
    void latitudeOutsideTheV48Range_isRejected() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("90.000001"), LNG, BigDecimal.TEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("latitude");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("-91"), LNG, BigDecimal.TEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("latitude");
    }

    @Test
    void longitudeOutsideTheV48Range_isRejected() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, new BigDecimal("180.000001"), BigDecimal.TEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("longitude");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, new BigDecimal("-181"), BigDecimal.TEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void zeroAndAboveCeilingRadius_areRejected() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, BigDecimal.ZERO))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("radiusKm");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("50.000001")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("radiusKm");
        // the ceiling itself (50) is IN — the (0, 50] interval
        SearchCriteria atCeiling = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("50"));
        assertThat(atCeiling.radiusMeters()).isEqualTo(50_000L);
    }

    @Test
    void subMeterRadiusPrecision_isRejected() {
        // 10.0004 km = 10000.4 m — finer than the meter: a 400, never a
        // silently-rounded radius (the key's whole-meter segment and the
        // ST_DWithin argument see the exact same value).
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("10.0004")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("meter");
    }

    @Test
    void searchCenter_isNormalizedToTheStoredCoordinateScale() {
        // 33.5588894 rounds to 33.558889 at scale 6 (HALF_UP) — the stored
        // columns are NUMERIC(9,6), so the effective center is the same
        // scale: the query and the cache key see the identical value.
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("33.5588894"), new BigDecimal("36.05694449"),
                new BigDecimal("10"));

        assertThat(criteria.latitude()).isEqualTo(new BigDecimal("33.558889"));
        assertThat(criteria.longitude()).isEqualTo(new BigDecimal("36.056944"));
        // and an already-scale-6 center passes through unchanged
        assertThat(new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, BigDecimal.TEN)
                .latitude().compareTo(LAT)).isZero();
    }

    @Test
    void legacyForms_haveNoRadius() {
        // every convenience construction path stays criterion-less for the
        // radius — pre-P1 call sites compile and behave unchanged.
        assertThat(new SearchCriteria("q", "cat", null, null).hasRadius()).isFalse();
        assertThat(new SearchCriteria(null, null, null, null, null, null).hasRadius()).isFalse();
        assertThat(new SearchCriteria(null, null, null, null, null, null, null).hasRadius()).isFalse();
    }

    @Test
    void radiusAndFacets_andWindow_compose() {
        // the radius ANDs with everything: window + facets + radius is one
        // legal criteria — the dispatch composes them (D-P10).
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                CHECK_IN, CHECK_OUT, 2, java.util.UUID.randomUUID(),
                com.marketplace.shared.api.PropertyPurpose.RENT,
                com.marketplace.shared.api.PropertyType.APARTMENT, 2, 1, 80,
                LAT, LNG, new BigDecimal("7.5"));

        assertThat(criteria.hasRadius()).isTrue();
        assertThat(criteria.hasWindow()).isTrue();
        assertThat(criteria.hasPropertyCriteria()).isTrue();
        assertThat(criteria.radiusMeters()).isEqualTo(7_500L);
    }
}
