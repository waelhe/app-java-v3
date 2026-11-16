package com.marketplace.community.spi;

import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.NeighborhoodPostCommentView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * Phase 7 (the corrected GraphQL plan §11-Phase-7 + the journey inventory
 * #599 §5 item 3 — the readiness doc's P0-1 closure, the FIRST
 * non-blocked composite candidate): the community module's post-detail
 * read SPI — the two bounded reads the post/source detail composite
 * journey consumes («قراءة مركبة تجمع التفاصيل والتعليقات»), each the
 * same bounded read its REST twin serves:
 *
 * <ul>
 *   <li>{@link #post(UUID, UUID)} — the single-post read the REST
 *       surface's {@code GET /api/v1/posts/{postId}} serves
 *       ({@code NeighborhoodPostService.getPost}): the stored facts plus
 *       the caller-scoped reaction facts plus the post's grouped media
 *       entries, in ONE answer (the readiness doc §4.3-3: «يجمع
 *       التفاصيل + الوسائط في إجابة واحدة»);</li>
 *   <li>{@link #comments(UUID, UUID, PagedRequest)} — the paged comment
 *       read the REST surface's {@code GET /api/v1/posts/{postId}/
 *       comments} serves ({@code NeighborhoodPostService.getComments}):
 *       the post's comments on the owner's complete sort key
 *       ({@code createdAt ASC, id ASC} — the owner pins the order; the
 *       request arrives unsorted by contract, the
 *       {@code NeighborhoodFeedPort} precedent).</li>
 * </ul>
 *
 * <p><b>Framework-neutral on purpose</b> (the 2026-09-25 port-audit
 * rule, the {@code NeighborhoodFeedPort} twin verbatim): the Spring Data
 * {@code Page} stays inside the adapter; the boundary speaks
 * {@link PagedRequest}/{@link PagedResponse}.</p>
 *
 * <p><b>The gates are the owner's own</b> (the inventory §8: «لا يعتبر
 * المعرّف إذنًا» — the id is re-validated at open time, never trusted):
 * both legs run the SAME gate order the REST twins run — the post gate
 * first (an unknown, hidden or deleted post answers the honest 404 —
 * {@code ResourceNotFoundException} travels the seam), then the
 * membership gate in the post's OWN {@code locationId} (a caller with no
 * active membership, or a membership in a different neighborhood,
 * answers the REST read's own explicit denial —
 * {@code AccessDeniedException} travels the seam; the GraphQL resolver
 * classifies it to the AUTHZ taxonomy at the field level; the composite
 * never bends either owner's rules).</p>
 */
@NamedInterface("neighborhood-post-detail-spi")
public interface NeighborhoodPostDetailPort {

    /**
     * Reads ONE visible post's detail projection — the stored facts plus
     * the caller-scoped reaction facts ({@code reactionsCount}/
     * {@code reactedByMe}) plus the post's media entries, in one answer.
     *
     * @param callerId the authenticated caller's id (the membership
     *                 scope — resolved by the consuming surface's
     *                 principal seam, never a client parameter)
     * @param postId   the post's id — NOT permission: the read
     *                 re-validates the post's current state (VISIBLE
     *                 only) and the caller's active membership in the
     *                 post's own neighborhood at open time
     * @return the same-journey field set the REST single-post read
     *         serves (the same projection the feed page carries —
     *         nothing narrower, nothing wider)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when
     *         the post is unknown, hidden or deleted — the REST read's
     *         own honest 404 (a hidden post is absent exactly as an
     *         unknown id is)
     * @throws org.springframework.security.access.AccessDeniedException
     *         when the caller holds no active membership in the post's
     *         own neighborhood — the REST read's own 403 contract
     *         (G-N3), never a widened scope
     */
    NeighborhoodFeedPostView post(UUID callerId, UUID postId);

    /**
     * Reads one bounded page of the post's comments — the owner's
     * complete sort key ({@code createdAt ASC, id ASC}), reached through
     * the SAME post gate first.
     *
     * @param callerId the authenticated caller's id (the membership
     *                 scope — never a client parameter)
     * @param postId   the post's id — NOT permission: the read
     *                 re-validates the post's current state and the
     *                 caller's membership at open time, exactly as the
     *                 detail leg does
     * @param request  the framework-neutral pagination request
     *                 (unsorted — the comment thread's deterministic
     *                 order is the owner's contract, not a caller
     *                 vocabulary)
     * @return the page in the owner's complete sort key, mapped into the
     *         shared comment projection (the same-journey field set the
     *         REST comments read serves)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when
     *         the post is unknown, hidden or deleted — the honest 404
     *         (a hidden post's comments are absent exactly as the post
     *         itself is)
     * @throws org.springframework.security.access.AccessDeniedException
     *         when the caller holds no active membership in the post's
     *         own neighborhood — the REST read's own 403 contract
     */
    PagedResponse<NeighborhoodPostCommentView> comments(UUID callerId, UUID postId, PagedRequest request);
}
