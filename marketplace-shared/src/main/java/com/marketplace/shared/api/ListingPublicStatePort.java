package com.marketplace.shared.api;

import java.util.UUID;

/**
 * R5 (comprehensive-review-ar-fix plan §4/R5 — media privacy): read-only
 * port for the PUBLIC-VISIBILITY question of a listing, following the
 * {@link AvailabilityLookupPort} / {@link ListingPriceProvider} pattern —
 * the interface lives in shared-api, the CATALOG module (the data owner)
 * provides the implementation, the MEDIA module consumes it without ever
 * touching catalog internals.
 *
 * <p><b>Why a synchronous interface (the {@link ListingPriceProvider}
 * decision, same reasoning):</b> the media read needs the listing's public
 * state <em>before</em> generating presigned URLs — an asynchronous event
 * cannot satisfy a request-time visibility gate.</p>
 *
 * <p><b>The contract is the public listing read's own filter:</b>
 * "publicly visible" means ACTIVE — the exact predicate
 * {@code CatalogService.getActiveById} (the public detail endpoint's
 * resolver) applies: INACTIVE/PAUSED/ARCHIVED listings answer 404 on the
 * public surface, so their media answers 404 there too (appearance
 * consistency). An unknown or soft-deleted listing is not publicly
 * visible.</p>
 */
public interface ListingPublicStatePort {

    /**
     * Whether the listing is on the public read surface (ACTIVE per the
     * public listing contract). {@code false} covers every other state —
     * DRAFT, PAUSED, ARCHIVED, unknown id, soft-deleted row.
     */
    boolean isPubliclyVisible(UUID listingId);
}
