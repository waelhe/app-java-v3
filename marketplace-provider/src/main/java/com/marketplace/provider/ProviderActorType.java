package com.marketplace.provider;

/**
 * L36 (realestate systems plan §5 — agent/office pages): the provider
 * actor classification — فرد / وسيط مستقل / مكتب.
 *
 * <p>Display-only taxonomy: the classification drives the public profile
 * page and nothing else. No legal verification is implied by
 * {@code INDEPENDENT_BROKER} or {@code AGENCY} — the license number is a
 * public display field, and actual KYC sits behind its own documented gate
 * (realestate plan §7) that has not been opened.
 *
 * <p>Persistence: {@code VARCHAR(30)} with the V56 CHECK constraint
 * {@code chk_provider_profiles_actor_type} mirroring exactly these three
 * names (the house DB-CHECK-backs-the-enum discipline, V44/V48 precedent).
 * The DB default {@code 'INDIVIDUAL'} backfills every pre-L36 profile —
 * the honest classification, since the brokerage concept did not exist
 * before this layer.
 */
public enum ProviderActorType {
    INDIVIDUAL,
    INDEPENDENT_BROKER,
    AGENCY
}
