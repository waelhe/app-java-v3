package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The board's read model (L50): the stored facts plus the two
 * caller-scoped facts — the NeighborhoodEventView projection
 * discipline verbatim. The author stays an opaque UUID (the identity
 * seams own any resolution the client does); the pickup spot rides
 * the product's own {@code locationLabel} as the seller wrote it.
 *
 * <p><b>The two caller-scoped facts (the L47/L49 shape verbatim):</b>
 * {@code sellerVerified} (the author's OWN membership verification
 * state, read in ONE grouped batch over the page's author ids — the
 * badge «جار موثق» is earned, never claimed) and {@code mine} (the
 * caller's own authorship, so the client renders the withdraw button
 * honestly — the projection's one per-reader write fact).
 */
public record NeighborhoodMarketItemView(
        UUID id,
        UUID authorId,
        UUID locationId,
        String category,
        String title,
        String condition,
        Integer priceCents,
        String priceCurrency,
        String status,
        String locationLabel,
        boolean sellerVerified,
        boolean mine,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * The publish path's echo: the author's own fresh item — mine is
     * true by construction (the author fact needs no read), and the
     * verified badge rides the membership the publish gate itself
     * already read (the honest echo — never a guessed badge).
     */
    static NeighborhoodMarketItemView of(NeighborhoodMarketItem item, boolean sellerVerified) {
        return new NeighborhoodMarketItemView(
                item.getId(),
                item.getAuthorId(),
                item.getLocationId(),
                item.getCategory().name(),
                item.getTitle(),
                item.getCondition().name(),
                item.getPriceCents(),
                item.getPriceCurrency(),
                item.getStatus().name(),
                item.getLocationLabel(),
                sellerVerified,
                true,
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    /**
     * The board read's factory: the stored facts plus the two
     * caller-scoped facts the grouped badge batch and the authorship
     * comparison produced.
     */
    static NeighborhoodMarketItemView of(NeighborhoodMarketItem item, boolean sellerVerified,
                                         boolean mine) {
        return new NeighborhoodMarketItemView(
                item.getId(),
                item.getAuthorId(),
                item.getLocationId(),
                item.getCategory().name(),
                item.getTitle(),
                item.getCondition().name(),
                item.getPriceCents(),
                item.getPriceCurrency(),
                item.getStatus().name(),
                item.getLocationLabel(),
                sellerVerified,
                mine,
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
