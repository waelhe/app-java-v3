package com.marketplace.shared.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * L27 (feature-expansion roadmap §5): read-only port for cross-module
 * availability lookups, following the {@link ProviderLookupPort} pattern —
 * the interface lives in shared-api, the availability module provides the
 * implementation, the search module consumes it without ever touching
 * availability internals.
 *
 * <p>Semantics — the bulk form of {@code AvailabilityService.isAvailable}
 * (the per-provider predicate, exact same strict inequalities): a provider
 * is available for the window when
 * <ul>
 *   <li>it has at least one slot with {@code booked = false} whose
 *       {@code starts_at < endsAt} and {@code ends_at > startsAt} — an open
 *       interval overlap, so a slot ending exactly at {@code startsAt} does
 *       not qualify (and does not conflict either: the exclusive-end
 *       convention), and</li>
 *   <li>it has no time-off whose {@code starts_at < endsAt} and
 *       {@code ends_at > startsAt} — again open-interval overlap, so a
 *       time-off ending exactly at {@code startsAt} is not a conflict.</li>
 * </ul>
 */
public interface AvailabilityLookupPort {

    /**
     * All providers that are available for the given window (free slot
     * overlapping, no time-off overlapping — see the class contract).
     * The empty set is a valid answer: nobody qualifies, and the caller is
     * expected to short-circuit to an honest empty page.
     *
     * @param startsAt window start (inclusive side of {@code [startsAt, endsAt)})
     * @param endsAt   window end (exclusive side)
     */
    Set<UUID> findAvailableProviderIds(Instant startsAt, Instant endsAt);
}
