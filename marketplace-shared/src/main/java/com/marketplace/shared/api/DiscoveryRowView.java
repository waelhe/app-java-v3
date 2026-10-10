package com.marketplace.shared.api;

import java.util.List;

/**
 * One discovery rail as rendered: the rail type plus its eligible cards.
 *
 * <p><b>The no-empty-rail guarantee lives here</b> (JT-20 / AC-20-07):
 * the assembler never emits a row with an empty card list — an
 * ineligible/quiet rail is simply ABSENT from the discovery response, and
 * an "empty state" is an honest client-side rendering of that absence
 * (UJ-265), never a fabricated placeholder card. {@code totalEligible}
 * carries the source-side count so "عرض الكل" (the full topic page,
 * UJ-261) can state the real size before the user commits to navigating.</p>
 *
 * <param name="row">which rail this is</param>
 * <param name="cards">the eligible cards, deduped by (sourceType,
 *     sourceId), in the rail's deterministic order</param>
 * <param name="totalEligible">the total number of eligible source records
 *     behind this rail (may exceed cards.size() — the rail shows a bounded
 *     page; the full filtered list lives on the topic page)</param>
 */
public record DiscoveryRowView(
        DiscoveryRowType row,
        List<DiscoveryCardView> cards,
        long totalEligible) {

    public DiscoveryRowView {
        cards = List.copyOf(cards);
    }
}
