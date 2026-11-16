package com.marketplace.community;

/**
 * L50 (the Nextdoor-2026 completeness wave — gap #5, the market board):
 * a market item's category — the board's one filter axis (the
 * Specifications {@code hasCategory} rides this enum; the V90 DB CHECK
 * pins the SQL side per D-N7).
 *
 * <p>The vocabulary is the PRODUCT's own — the frontend contract
 * {@code src/lib/neighborhood-design.ts} {@code MARKET_CATEGORIES}
 * measured verbatim (the design's five filter chips), so the Java enum,
 * the DB CHECK and the product surface share one membership with zero
 * translation: {@code FREE} و{@code FURNITURE} و{@code ELECTRONICS}
 * و{@code TOOLS} و{@code OTHER}.
 *
 * <p>{@code FREE} is not a subject like the other four — it is the
 * product's gift band («مقتنيات مجانية», ركن الإهداء): a FREE item
 * carries no price at all (the V90 pricing CHECK's own law — «مجاني
 * ⇔ بلا سعر»), which is why the category and the price form ONE rule,
 * exactly like the events' registration/capacity pair.
 *
 * <p>Widening this vocabulary later is the L43 sequence again: one
 * CHECK-widening migration plus this enum's one constant, nothing else
 * — the board's reads and filters ride the same axis untouched.
 */
public enum MarketCategory {
    FREE,
    FURNITURE,
    ELECTRONICS,
    TOOLS,
    OTHER
}
