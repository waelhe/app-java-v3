package com.marketplace.community;

/**
 * L50 (the Nextdoor-2026 completeness wave — gap #5, the market board):
 * a market item's condition — the card's own chip (the design's
 * «كالجديد» / «جيد» vocabulary, measured verbatim from the display
 * dataset — every card carries one of exactly these two). The V90 DB
 * CHECK pins the same membership on the SQL side per D-N7.
 *
 * <p>Widening later (a «جديد» / «مقبول» tiering, a Yelp-style
 * condition ladder) is the L43 sequence again: one CHECK-widening
 * migration plus this enum's constant — the read contract already
 * carries the field, so no surface change rides the widening.
 */
public enum MarketCondition {
    LIKE_NEW,
    GOOD
}
