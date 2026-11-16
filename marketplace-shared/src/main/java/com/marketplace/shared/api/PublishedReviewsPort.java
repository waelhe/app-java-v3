package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port for the provider public page's reviews block (W1 — yelp-level plan
 * §4.4/§4.5): the PUBLISHED forward page of one provider's reviews,
 * composed with the reviewer-identity and helpful-vote blocks.
 *
 * <p>The {@code CatalogSearchPort.listActiveByProvider} precedent
 * verbatim (the same page the provider public page already asks the
 * catalog for its listings block): the port speaks the NEUTRAL paging
 * contracts — {@link PagedRequest} in, {@link PagedResponse} out, no
 * framework types (the shared-contract module is the last place Spring
 * types should leak; {@code SpringPagination} is the documented interop
 * corner each side translates through).
 *
 * <p>This port closes the declared ID-space seam: the public page is
 * addressed by the provider PROFILE id while reviews are keyed by the
 * provider USER id — the owning service holds the mapping internally and
 * the composed block carries no user id at all.
 *
 * <p>Semantics (the plan's named public read path 2, the PUBLISHED gate):
 * newest first, forward direction only; pending and moderator-hidden rows
 * are absent — the same rows {@code GET /api/v1/reviews/provider/{userId}}
 * serves, paged.
 */
public interface PublishedReviewsPort {

    /**
     * One page of a provider's PUBLISHED forward reviews, composed with the
     * three batch-resolved identity blocks (names, published counts,
     * helpful-vote counts — a whole page costs a bounded number of queries,
     * never per-review).
     *
     * @param providerUserId the provider's USER id (the {@code reviews.provider_id}
     *                       space, A1) — resolved by the caller, never exposed
     *                       on any public response
     * @param request        the neutral page request
     */
    PagedResponse<PublishedReviewView> findPublishedByProviderUserId(UUID providerUserId, PagedRequest request);

    /**
     * W2 (greptile round 2, adopted from the root): the bounded LEADING
     * sample of one origin's PUBLISHED population — newest first, the same
     * complete ordering key and identity blocks as the paged read, bounded
     * by the caller's declared sample width. This is the read a
     * structured-data block composes: its sample must ride the population
     * the aggregate beside it describes, never a filter of the caller's
     * requested page (which a mode-switch leftover can leave empty of that
     * origin while the aggregate still reports its reviews).
     *
     * @param providerUserId the provider's USER id (the {@code reviews.provider_id}
     *                       space, A1)
     * @param origin         the origin vocabulary value the population is
     *                       scoped to — {@link PublishedReviewView#ORIGIN_BOOKING}
     *                       or {@link PublishedReviewView#ORIGIN_ORGANIC}
     *                       (the V85 CHECK-held vocabulary, spoken as the
     *                       shared contract's own string form)
     * @param limit          the sample width (the rich-results convention —
     *                       a handful, never a whole page)
     */
    java.util.List<PublishedReviewView> findPublishedSampleByProviderUserIdAndOrigin(
            UUID providerUserId, String origin, int limit);
}
