package com.marketplace.identity;

import com.marketplace.shared.api.ProviderSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — G21): the follow row as the /me surface renders
 * it — composed with the followed provider's current public identity.
 *
 * <p>{@code providerId} is the provider PROFILE id (the public page's own
 * key — the client's navigation link), resolved back from the stored user
 * id at read time; the provider's user id never appears (the public-page
 * id-space discipline). The honest fallback for a provider row the batch
 * lookup cannot see (a soft-deleted profile) is the null pair — the row
 * itself stays (the member's own record, b-5), rendered without its link.
 */
public record ProviderFollowView(
        UUID id,
        UUID providerId,
        String providerDisplayName,
        Instant createdAt
) {

    static ProviderFollowView of(ProviderFollow follow, ProviderSummary provider) {
        return new ProviderFollowView(
                follow.getId(),
                provider != null ? provider.id() : null,
                provider != null ? provider.displayName() : null,
                follow.getCreatedAt());
    }
}
