package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Port for the provider public page's reviews block (W1 — yelp-level plan
 * §4.4/§4.5): the PUBLISHED forward page of one provider's reviews,
 * composed with the reviewer-identity and helpful-vote blocks.
 *
 * <p>The {@code ReviewStatsPort} precedent verbatim: the provider module
 * composes its public page and resolves review data through a shared-api
 * port implemented by the reviews module (the module law — cross-module
 * traffic rides SPI ports, never direct repository or service imports).
 * This port closes the declared ID-space seam: the public page is
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
     * @param pageable       the page to serve
     */
    Page<PublishedReviewView> findPublishedByProviderUserId(UUID providerUserId, Pageable pageable);
}
