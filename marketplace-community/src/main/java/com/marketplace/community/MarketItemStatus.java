package com.marketplace.community;

/**
 * L50 (the Nextdoor-2026 completeness wave — gap #5, the market board):
 * a market item's product state — the board's own two-state vocabulary
 * ({@code ACTIVE} «متاح» / {@code SOLD} «تم البيع»). The V90 DB CHECK
 * pins the same membership on the SQL side per D-N7.
 *
 * <p>The states are the CARD's display states, not lifecycle gates: a
 * SOLD row STAYS on the board (the grid renders its state — the
 * buyer-side social proof the design shows), and the author's withdraw
 * is the house soft delete, never a status value. A mark-sold write
 * (the seller closing their own listing after a face-to-face deal) is
 * a documented product decision that rides a future wave; the column
 * exists from day one so the read contract is complete (the V25/V32
 * lesson — the events' {@code featured} flag's own stance).
 */
public enum MarketItemStatus {
    ACTIVE,
    SOLD
}
