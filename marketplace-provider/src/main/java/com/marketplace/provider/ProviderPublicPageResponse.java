package com.marketplace.provider;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PublishedReviewView;

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
 * come from ONE fresh {@code ReviewStatsPort} snapshot so the two numbers
 * are always consistent with each other; a provider with no live reviews
 * carries a null average and zero count (the honest "not yet rated" — the
 * aggregate query's GROUP BY produces no row).
 *
 * <p><b>W1 (§4.4 — the dual badges):</b> the block is composed per the
 * active {@code reviews.mode}. VERIFIED_ONLY (the seed): the verified
 * aggregate alone — byte-identical to the pre-W1 page.
 * OPEN: the two aggregates merged into one number (the plan's "يُدمج
 * المجموعان في رقم واحد") — the general pair stays null/0 on the
 * response. HYBRID: the verified aggregate in the main fields plus the
 * general pair in {@code ratingGeneralAverage}/{@code ratingGeneralCount}
 * — the strongest trust display ("موثّق 4.8 (23) · عام 4.2 (156)").
 *
 * <p><b>W1 (§4.1 — the mode on the page):</b> {@code reviewsMode} carries
 * the active mode so the surface renders honestly instead of guessing
 * (a HYBRID page with zero general reviews is indistinguishable from
 * VERIFIED_ONLY by the rating fields alone). The mode is the owner's
 * runtime key — this field is the read, never a write.
 *
 * <p><b>W1 (§4.4/§4.5 — the reviews block, closing the declared §10
 * seam):</b> the page now composes the provider's PUBLISHED forward
 * reviews page ({@code reviews}) through the shared
 * {@code PublishedReviewsPort} — the profile-to-user mapping stays
 * inside the owning service (the response still declares NO user id),
 * the rows carry the reviewer-identity and helpful-vote blocks, and the
 * public page becomes the reviews list's public home (votes, flags and
 * the organic write's context were contract-served with no surface
 * before).
 *
 * @param listings the provider's ACTIVE listings page — empty (total 0)
 *                 whenever the profile is not VERIFIED: the public page
 *                 hides a suspended broker's inventory (the layer's own
 *                 gate; the global browse/search surfaces keep their
 *                 existing ACTIVE-only contracts, documented in the plan).
 * @param reviews  the provider's PUBLISHED forward reviews page — the
 *                 forward surface only, newest first; the block is NOT
 *                 VERIFIED-gated (reviews are the reviewed party's
 *                 public record, not inventory).
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
        String reviewsMode,
        Double ratingAverage,
        long reviewCount,
        Double ratingGeneralAverage,
        long ratingGeneralCount,
        PagedResponse<PublishedReviewView> reviews,
        PagedResponse<ListingSummary> listings
) {
}
