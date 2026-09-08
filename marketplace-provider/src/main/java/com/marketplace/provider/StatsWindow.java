package com.marketplace.provider;

import com.marketplace.shared.api.BadRequestException;

import java.time.Duration;
import java.time.Instant;

/**
 * L25 (feature-expansion roadmap §5): the stats window — the type IS the
 * gate (the L27 SearchCriteria lesson: the record's canonical constructor
 * validates before any query runs).
 *
 * <p>Rules (acceptance criterion 3): the default window is the last 30 days;
 * the maximum is one year; an incomplete (one bound without the other),
 * reversed, or zero-length window is a 400 at construction, never a
 * silently-degenerate read. The window follows the house
 * {@code [from, to)} exclusive-end convention — the same one the slot
 * stats, the booking count and the search stay-window use, so all
 * aggregates measure the same span.
 */
public record StatsWindow(Instant from, Instant to) {

    private static final Duration DEFAULT_LENGTH = Duration.ofDays(30);
    private static final Duration MAX_LENGTH = Duration.ofDays(365);

    public StatsWindow {
        if (from == null || to == null) {
            throw new BadRequestException("stats window bounds must be provided together");
        }
        if (!from.isBefore(to)) {
            throw new BadRequestException("stats window must be a positive [from, to) span");
        }
        if (Duration.between(from, to).compareTo(MAX_LENGTH) > 0) {
            throw new BadRequestException("stats window must not exceed one year");
        }
    }

    /**
     * The default window: the last 30 days ending at {@code now}
     * (roadmap L25: "النافذة الافتراضية 30 يوماً").
     */
    public static StatsWindow lastThirtyDays(Instant now) {
        return new StatsWindow(now.minus(DEFAULT_LENGTH), now);
    }

    static Duration defaultLength() {
        return DEFAULT_LENGTH;
    }

    static Duration maxLength() {
        return MAX_LENGTH;
    }
}
