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

    @Test
    void windowRidesTheCacheKeyCarrier() {
        // The search-results-v2 cache key is the criteria's toString() — the
        // window components must appear in it so two different windows never
        // share a cached entry (the roadmap's "extend the cache key with the
        // window", pinned structurally here and by the files guard).
        String keyCarrier = new SearchCriteria("q", null, null, null, CHECK_IN, CHECK_OUT).toString();

        assertThat(keyCarrier).contains("checkIn=" + CHECK_IN).contains("checkOut=" + CHECK_OUT);
    }
}
