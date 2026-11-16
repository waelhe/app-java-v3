package com.marketplace.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): the seller summary's
 * scalar half — a CLOSED projection (Spring Data
 * {@code reference/repositories/projections.html}: interface-based, the
 * getter names bound to the query's select aliases). The plan's own
 * wording makes this the wave's signature mechanism: «الإسقاط المغلق
 * للملخص» — the summary IS the closed projection.
 *
 * <p>Closed means closed: the interface exposes exactly the aggregate
 * facts the query computes ({@code sellerId}, {@code productCount},
 * {@code lastActivityAt}) and nothing of the entity's own column set —
 * the projection never widens into an open (dynamic) one, which the
 * official document itself describes as the performance-heavier path.
 * The proxy is backed by the aggregate query's tuple, not by a managed
 * {@code Product} — the read never hydrates the store's root entity.
 */
public interface SellerSummaryView {

    /** The summarized seller — the group-by key (the query's {@code sellerId} alias). */
    UUID getSellerId();

    /** The seller's live product count (the {@code productCount} alias). */
    long getProductCount();

    /** The seller's last product touch (the {@code lastActivityAt} alias — max updated_at). */
    Instant getLastActivityAt();
}
