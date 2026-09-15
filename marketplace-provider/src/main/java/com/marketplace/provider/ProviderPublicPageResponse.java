package com.marketplace.provider;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * L36 (realestate systems plan §5 — agent/office pages): the public
 * provider page — the one composite read
 * {@code GET /api/v1/providers/{id}/public} returns.
 *
 * <p><b>Acceptance criterion 3 (no private contact data):</b> the field
 * set below IS the whitelist — display name, bio, the L36 persona fields,
 * the lifecycle status (the VERIFIED badge source), the rating block and
 * the ACTIVE listings page. No email, no phone, no user id, no role: the
 * record cannot leak what it does not declare.
 *
 * <p>Rating block semantics: {@code ratingAverage}/{@code reviewCount}
 * come from ONE fresh {@code ReviewStatsPort} snapshot (the same aggregate
 * the L21 listener maintains) so the two numbers are always consistent
 * with each other; a provider with no live reviews carries a null average
 * and zero count (the honest "not yet rated" — the aggregate query's GROUP
 * BY produces no row).
 *
 * @param listings the provider's ACTIVE listings page — empty (total 0)
 *                 whenever the profile is not VERIFIED: the public page
 *                 hides a suspended broker's inventory (the layer's own
 *                 gate; the global browse/search surfaces keep their
 *                 existing ACTIVE-only contracts, documented in the plan).
 */
public record ProviderPublicPageResponse(
        UUID id,
        String displayName,
        String bio,
        ProviderStatus status,
        ProviderActorType actorType,
        String agencyName,
        String licenseNumber,
        Instant createdAt,
        Double ratingAverage,
        long reviewCount,
        PagedResponse<ListingSummary> listings
) {
}
