package com.marketplace.shared.api;

/**
 * The public marketplace search orchestration port.
 *
 * <p>Callers that need marketplace search results use this port rather than
 * selecting catalog repository operations themselves. The search module owns
 * dispatch between text search, structured filters, availability, geographic
 * scope, real-estate facets, sort validation, deterministic ordering, and
 * cache semantics. This keeps REST and AI/tool callers on the same behavior
 * while leaving the catalog port as an implementation detail of the search
 * pipeline.
 *
 * <p>The contract is framework-neutral: it uses {@link SearchCriteria},
 * {@link PagedRequest}, and {@link PagedResponse}, not Spring Data types.
 */
public interface MarketplaceSearchPort {

    /**
     * Execute the canonical marketplace search for the supplied criteria.
     * Sorting uses the same public vocabulary accepted by the search REST
     * surface (for example {@code price}, {@code newest}, {@code rating},
     * {@code area}, and {@code distance}); invalid combinations are rejected
     * before a repository query is issued.
     */
    PagedResponse<ListingSummary> search(SearchCriteria criteria, PagedRequest request);

    /**
     * Stage 5 (community platform execution plan — the unified legal
     * multi-domain search): the merged multi-domain answer. REST and AI
     * callers ride THIS one method (parity by construction — the same
     * bean, the same orchestration, the same visibility predicates); the
     * domain adapters are implementation details of the search pipeline.
     */
    UnifiedSearchResponse unified(UnifiedSearchQuery query);
}
