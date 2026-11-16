package com.marketplace.provider;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L25 (feature-expansion roadmap §5, acceptance criterion 3): the stats
 * window IS the input gate — default 30 days, maximum one year, and the
 * record's canonical constructor rejects an incomplete, reversed or
 * zero-length window (the L27 SearchCriteria lesson: an invalid window
 * cannot be constructed, by any caller, so no downstream code ever sees
 * one).
 */
class StatsWindowTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void defaultWindow_isTheLastThirtyDays() {
        StatsWindow window = StatsWindow.lastThirtyDays(NOW);

        assertThat(window.from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(window.to()).isEqualTo(NOW);
        assertThat(Duration.between(window.from(), window.to())).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void validWindow_isAccepted() {
        StatsWindow window = new StatsWindow(NOW.minusSeconds(3600), NOW);

        assertThat(window.from()).isBefore(window.to());
    }

    @Test
    void exactlyOneYear_isTheMaximumAcceptedSpan() {
        StatsWindow year = new StatsWindow(NOW.minus(Duration.ofDays(365)), NOW);

        assertThat(Duration.between(year.from(), year.to())).isEqualTo(Duration.ofDays(365));
    }

    @Test
    void longerThanOneYear_isRejected() {
        assertThatThrownBy(() -> new StatsWindow(NOW.minus(Duration.ofDays(366)), NOW))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("one year");
    }

    @Test
    void incompleteWindow_isRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> new StatsWindow(null, NOW))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> new StatsWindow(NOW, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("together");
    }

    @Test
    void reversedWindow_isRejected() {
        assertThatThrownBy(() -> new StatsWindow(NOW, NOW.minusSeconds(1)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void zeroLengthWindow_isRejected() {
        assertThatThrownBy(() -> new StatsWindow(NOW, NOW))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("positive");
    }
}
