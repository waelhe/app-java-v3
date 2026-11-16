package com.marketplace.community.spi;

import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * Phase 6 (the corrected GraphQL plan §11-Phase-6 — the first composite
 * user journey per the inventory #599 §7 stage 4: «قراءة مركبة
 * للاكتشاف/موجز الحي»): the community module's feed read SPI — the
 * same bounded read the REST feed serves ({@code GET /api/v1/
 * neighborhood/posts} → {@code NeighborhoodPostService.getFeed}): the
 * caller's OWN neighborhood, VISIBLE posts only, on the complete sort
 * key ({@code createdAt DESC, id DESC} — the owner pins the order; the
 * request arrives unsorted by contract, the
 * {@code CommunityPostSearchPort} precedent).
 *
 * <p><b>Framework-neutral on purpose</b> (the 2026-09-25 port-audit
 * rule, the {@code CommunityPostSearchPort} twin): the Spring Data
 * {@code Page} stays inside the adapter; the boundary speaks
 * {@link PagedRequest}/{@link PagedResponse}.</p>
 *
 * <p><b>The membership gate is the owner's own</b> (the inventory §8:
 * «لا يعتبر المعرّف إذنًا» — the membership IS the scope, there is no
 * location parameter on this contract): a caller with no active
 * membership answers the REST feed's own explicit denial
 * ({@code AccessDeniedException} travels the seam — the GraphQL
 * resolver classifies it to the AUTHZ taxonomy at the field level; the
 * composite never bends either owner's rules).</p>
 */
@NamedInterface("neighborhood-feed-spi")
public interface NeighborhoodFeedPort {

    /**
     * Reads one bounded page of the caller's neighborhood live feed.
     *
     * @param callerId the authenticated caller's id (the membership
     *                 scope — resolved by the consuming surface's
     *                 principal seam, never a client parameter)
     * @param request  the framework-neutral pagination request
     *                 (unsorted — the feed's deterministic order is the
     *                 owner's contract, not a caller vocabulary)
     * @return the page in the owner's complete sort key, mapped into the
     *         shared feed projection (the same-journey field set the
     *         REST feed view carries)
     * @throws org.springframework.security.access.AccessDeniedException
     *         when the caller holds no active neighborhood membership —
     *         the REST feed's own 403 contract (G-N3), never a widened
     *         scope
     */
    PagedResponse<NeighborhoodFeedPostView> feed(UUID callerId, PagedRequest request);
}
