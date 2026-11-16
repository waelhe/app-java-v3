package com.marketplace.shared.api;

/**
 * The unified-search domain vocabulary (§5.1 — «مدخل موحد للمستخدم بحسب
 * المجال والجغرافيا»). A domain joins this enum ONLY when its source has a
 * measured contract and test evidence behind it — the plan's rule that no
 * adapter lands before its source record (ownership, state, eligibility,
 * fields, failure mode) is measured.
 *
 * <p><b>Connected sources:</b> {@link #LISTINGS} rides the standing
 * {@link MarketplaceSearchPort} (the #524 orchestration: text, filters,
 * geo tree, facets, sort validation); {@link #COMMUNITY_POSTS} rides the
 * community module's Arabic-FTS post search (V166–V169: PostgreSQL FTS
 * primary + pg_trgm fallback, membership-scoped visibility).
 *
 * <p><b>Deliberately NOT connected yet:</b> official messages/broadcast
 * (their source wave is still an open branch), provider directory search
 * (no search contract measured yet), knowledge/directory entities (no
 * contract). Each joins behind its own measured port — never by widening
 * this dispatch with an unmeasured adapter.
 */
public enum UnifiedSearchDomain {
    LISTINGS,
    COMMUNITY_POSTS
}
