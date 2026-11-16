package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L48 (the Nextdoor-2026 completeness wave — gap #2, post images): the port
 * that lets the media module resolve a neighborhood post as an attachment
 * TARGET without depending on the community module directly — the exact
 * {@code ListingPriceProvider} house pattern (the interface lives in
 * shared-api, the data owner implements it, the consumer injects it; no
 * module boundary is crossed in code), applied to the second target of the
 * generalized media line (D-C3's documented decision: "PR يعمّم الهدف").
 *
 * <p><b>Design decision — synchronous interface vs. event:</b> the caller
 * ({@code MediaService.requestPostUpload}) needs the post's author
 * <em>before</em> signing anything — the presigned object key embeds the
 * post id and the ownership gate compares the caller to the author, both
 * atomically within the same operation. An asynchronous event cannot
 * satisfy that; the Modulith guidance accepts synchronous queries when the
 * result is needed within the current operation (the
 * {@code ListingPriceProvider}/{@code BookingParticipantProvider}
 * precedent verbatim).</p>
 *
 * <p><b>The VISIBLE-only contract:</b> only a post the feed itself would
 * return resolves here — unknown, hidden (moderated) or soft-deleted posts
 * answer the honest 404, exactly the comment gate's {@code visiblePost}
 * behavior. A hidden post cannot gain photos; attaching to the dead is
 * nonsense the gate refuses at the seam.</p>
 */
public interface PostLookupPort {

    /**
     * Resolves one VISIBLE post as a media attachment target.
     *
     * @param postId the community module's post id
     * @return the post's author carrier — {@code authorId} is a plain user
     *         id in the identity seams' space (never a JPA relation across
     *         the boundary, the V32/V61 discipline), and
     *         {@code authorMayWriteCommunity} is the author's CURRENT
     *         community-write right as the membership domain computes it
     *         (D-N3: every verification state except REJECTED passes; an
     *         absent membership fails — a non-member holds no community
     *         write). The #484 review round added the carrier leg so the
     *         media line's write paths can honor the SAME gate the post
     *         service's own publish/comment/react commands honor, without
     *         the media module ever depending on the community module.
     * @throws ResourceNotFoundException if the post does not exist, is
     *         hidden, or is soft-deleted
     */
    PostInfo getPostInfo(UUID postId);

    /**
     * Carrier of the post facts the media attachment flow needs. Immutable
     * value object — no behaviour.
     *
     * @param authorMayWriteCommunity the author's current community-write
     *        right (D-N3) — {@code false} for a REJECTED membership or no
     *        active membership; the media write paths refuse with the
     *        post service's own 403 when it is {@code false}
     */
    record PostInfo(UUID postId, UUID authorId, boolean authorMayWriteCommunity) {
    }
}
