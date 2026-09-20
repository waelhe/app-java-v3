package com.marketplace.community;

/**
 * L42 (neighborhood community plan §5): a neighborhood post's category —
 * the feed's one filter axis (D-N5's Specifications {@code hasCategory}
 * rides this enum; the DB CHECK pins the SQL side per D-N7).
 *
 * <p>The vocabulary is the plan's three L42 values plus L43's own
 * {@code RECOMMENDATION} — added by the CHECK-widening pair V68/V69
 * exactly as L42's javadoc reserved it ("a CHECK-widening migration plus
 * this enum's one constant, nothing else" — the plan's §5-L43: "الفئات
 * القديمة سلوكها بايت-بايت").
 *
 * <p>{@code RECOMMENDATION}'s semantics (the plan's own words): a
 * neighbor asking for a local-service recommendation («أبحث عن سبّاك
 * موثوق») or offering one — authored text in the same feed, behind the
 * same membership gate, on the same one filter axis. The organized
 * business directory and its structured fields stay OUT (G-N4 — a
 * product gate behind the plan's §7, not a layer).
 */
public enum PostCategory {
    GENERAL,
    CLASSIFIED,
    LOST_FOUND,
    RECOMMENDATION
}
