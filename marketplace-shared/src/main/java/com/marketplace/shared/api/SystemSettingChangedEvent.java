package com.marketplace.shared.api;

import java.time.Instant;

/**
 * W0 (yelp-level plan §4.6): a platform setting changed.
 *
 * <p><b>Why published (the {@link ReviewCreatedEvent} /
 * {@code ContentModeratedEvent} pattern — SYSTEM.md §8):</b> the cache eviction
 * is a mechanism inside the owning module, but «من غيّر ومتى وإلى ماذا» is a
 * fact the platform may want to react to — audit trails, alerting on a
 * trust-shape switch, or a later notification wave. Publishing it here keeps the
 * decision decoupled from the discovery, exactly as {@code CacheConfig} is
 * kept out of {@code CacheInvalidationRelay}: the decision (what to evict) is
 * the publisher's, the mechanism (how) is the listener's.
 *
 * <p>{@code oldValue}/{@code newValue} carry the setting's JSON value in its
 * native JSON type (string/number/boolean/array/object as stored) so no consumer
 * has to know the reader-side typing to answer «إلى ماذا».
 */
public record SystemSettingChangedEvent(
        String key,
        Object oldValue,
        Object newValue,
        String actor,
        Instant changedAt) {
}
