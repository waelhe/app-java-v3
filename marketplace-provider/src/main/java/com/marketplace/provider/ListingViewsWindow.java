package com.marketplace.provider;

import com.marketplace.shared.api.BadRequestException;

import java.time.LocalDate;
import java.util.Set;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider's views
 * window — the type IS the gate (the L27 SearchCriteria lesson, the same
 * one {@link StatsWindow} applies to the from/to form): the plan offers
 * exactly three windows ("نوافذ 7/30/90 يومًا — نمط StatsWindow القائم"),
 * so anything outside the whitelist is a 400 at construction, before any
 * query — never a silently-degenerate read.
 *
 * <p>Day semantics: the window covers {@code days} UTC day buckets ending
 * TODAY inclusive — the newest bucket (today, still accumulating) is part
 * of every window, because an analytics surface that hides today's views
 * answers a question nobody asked. {@code 7} = today minus 6 .. today.
 * The default is 30, the house stats default ({@code StatsWindow}'s own
 * default length).
 */
public record ListingViewsWindow(int days) {

    static final int DEFAULT_DAYS = 30;
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);

    public ListingViewsWindow {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BadRequestException(
                    "views window days must be one of 7, 30 or 90 — got " + days);
        }
    }

    /**
     * The requested window, or the default 30 when the caller omitted the
     * parameter entirely.
     */
    static ListingViewsWindow ofDays(Integer requested) {
        return new ListingViewsWindow(requested == null ? DEFAULT_DAYS : requested);
    }

    /**
     * The window's first UTC day (inclusive) — {@code days} buckets back
     * from {@code today}, which is itself included.
     */
    LocalDate sinceInclusive(LocalDate today) {
        return today.minusDays(days - 1L);
    }
}
