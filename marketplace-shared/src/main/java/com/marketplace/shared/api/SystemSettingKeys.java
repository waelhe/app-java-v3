package com.marketplace.shared.api;

/**
 * W0 (yelp-level plan §4.1 + §5 — the control layer): the platform's setting
 * key space, closed.
 *
 * <p><b>Why a closed space (the INV-2 discipline applied to keys):</b> a key is
 * a contract between the write surface (the admin API) and a reader that lives
 * in a different module — {@code reviews.mode} is read by the review-creation
 * gate (W1). A key written as a literal at each call site can drift from the
 * literal the seed row carries, and the failure is silent: the reader finds no
 * row, falls back to its default, and the control surface changes nothing while
 * looking healthy. Constants make the pair compile-checked, and the V71 seed
 * uses these exact literals in SQL.
 *
 * <p><b>Shape</b> (the V71 {@code chk_system_settings_key} constraint is its
 * database twin): lowercase dotted identifier — {@code reviews.mode},
 * {@code reviews.organic.daily-cap}. The constraint is the authority at rest;
 * these constants are the authority in code.
 */
public final class SystemSettingKeys {

    /**
     * §4.1 — the review-creation gate: {@code VERIFIED_ONLY | OPEN | HYBRID}.
     * V71 seeds it as {@code VERIFIED_ONLY}, which is today's behaviour byte for
     * byte, so W0's acceptance criterion holds: zero visible behaviour change.
     */
    public static final String REVIEWS_MODE = "reviews.mode";

    private SystemSettingKeys() {
    }
}
