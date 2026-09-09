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
}
