package com.marketplace.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * L33 (realestate systems plan §5): the classifieds lifecycle policy —
 * the MediaProperties pattern (module-owned, type-safe, constructor
 * binding, primed sections per the AGENTS.md binding rule).
 *
 * <p>{@code expiryDays}: NULL by design (no code default) — a deployment
 * that wants listings to expire MUST declare the policy (yml or
 * {@code MARKETPLACE_CATALOG_EXPIRY_DAYS}); with the property unset, an
 * activation without an explicit date answers 409 ("no silently-immortal
 * listing" — the acceptance criterion). The house yml declares 90 (the
 * global classifieds convention range).
 *
 * <p>{@code renewalCooldownDays}: the anti-recycling floor — at most one
 * renewal per window per listing (default 1: a same-day second renewal is
 * refused with 409). Always-on (a safe default), env-tunable.
 */
@ConfigurationProperties(prefix = "marketplace.catalog")
public record CatalogProperties(
        @DefaultValue Expiry expiry
) {

    public record Expiry(
            /** Days a listing's publication lasts — null = policy unset. */
            Integer expiryDays,
            /** Minimum days between two renewals of one listing. */
            @DefaultValue("1") Integer renewalCooldownDays
    ) {
    }
}
