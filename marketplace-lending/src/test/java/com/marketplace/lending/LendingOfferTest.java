package com.marketplace.lending;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 8 (ADR-0004) — the offer's terms law: the non-negative money rule
 * and the period fee's whole-day ceiling (a partial day rents the whole
 * day — the settlement's own arithmetic rides the same semantics).
 */
class LendingOfferTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    void theTermsAreNonNegativeMoney() {
        LendingOffer offer = LendingOffer.publish(UUID.randomUUID(), UUID.randomUUID(),
                1500L, "SAR", 5000L, 200L);
        assertThatThrownBy(() -> offer.updateTerms(-1L, 5000L, 200L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offer.updateTerms(1500L, -1L, 200L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offer.updateTerms(1500L, 5000L, -1L))
                .isInstanceOf(IllegalArgumentException.class);

        offer.updateTerms(2000L, 6000L, 300L);
        assertThat(offer.getDailyFeeMinor()).isEqualTo(2000L);
        assertThat(offer.getDepositMinor()).isEqualTo(6000L);
        assertThat(offer.getLateFeePerDayMinor()).isEqualTo(300L);
    }

    @Test
    void thePeriodFeeIsTheCeilingOfTheDaysTimesTheDailyRate() {
        LendingOffer offer = LendingOffer.publish(UUID.randomUUID(), UUID.randomUUID(),
                1500L, "SAR", 5000L, 200L);

        // Exactly three days: 3 × 1500.
        assertThat(offer.feeFor(START, END)).isEqualTo(4500L);
        // Three days plus six hours — the partial day rents the whole day.
        assertThat(offer.feeFor(START, END.plus(6, java.time.temporal.ChronoUnit.HOURS)))
                .isEqualTo(6000L);
        // A sub-day period still rents one whole day (the max(1, days) floor).
        assertThat(offer.feeFor(START, START.plus(1, java.time.temporal.ChronoUnit.HOURS)))
                .isEqualTo(1500L);
    }
}
