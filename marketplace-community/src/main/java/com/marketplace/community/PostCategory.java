package com.marketplace.community;

/**
 * L42 (neighborhood community plan §5): a neighborhood post's category —
 * the feed's one filter axis (D-N5's Specifications {@code hasCategory}
 * rides this enum; the DB CHECK pins the SQL side per D-N7).
 *
 * <p>The vocabulary is exactly the plan's three values. {@code RECOMMENDATION}
 * is L43's own point — a CHECK-widening migration plus this enum's one
 * constant, nothing else (the plan's §5-L43: "الفئات القديمة سلوكها
 * بايت-بايت" — the older categories' behavior byte-for-byte).
 */
public enum PostCategory {
    GENERAL,
    CLASSIFIED,
    LOST_FOUND
}
