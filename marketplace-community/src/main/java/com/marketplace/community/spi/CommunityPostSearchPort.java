package com.marketplace.community.spi;

import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.UnifiedSearchHit;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * The community post search SPI (§5.1) — the measured source contract the
 * unified search consumes. The visibility/membership eligibility lives in
 * the community path itself ({@code searchFeed}: the caller's OWN active
 * neighborhood, VISIBLE posts only, FTS primary + pg_trgm fallback); the
 * unified layer re-checks nothing and bypasses nothing — the contract IS
 * the eligibility enforcement point.
 *
 * <p>Framework-neutral on purpose (the 2026-09-25 port-audit rule): the
 * Spring Data page stays inside the adapter; the boundary speaks
 * {@link PagedRequest}/{@link PagedResponse}.
 */
@NamedInterface("community-post-search-spi")
public interface CommunityPostSearchPort {

    /**
     * Text-search the caller's own neighborhood's visible posts.
     *
     * @param callerId the caller's id (the membership scope — a caller with
     *                 no active membership is the documented 403)
     * @param query    the text query (the service's blank/200-code-point
     *                 gates apply)
     * @param request  the framework-neutral pagination request (unsorted —
     *                 relevance order is the contract)
     */
    PagedResponse<UnifiedSearchHit> search(UUID callerId, String query, PagedRequest request);
}
