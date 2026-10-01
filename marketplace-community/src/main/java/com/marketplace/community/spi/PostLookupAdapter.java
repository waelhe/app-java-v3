package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodMembership;
import com.marketplace.community.NeighborhoodMembershipRepository;
import com.marketplace.community.NeighborhoodPost;
import com.marketplace.community.NeighborhoodPostRepository;
import com.marketplace.community.PostStatus;
import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * L48 (the Nextdoor-2026 completeness wave — gap #2, post images): the
 * community module's implementation of the {@link PostLookupPort}
 * cross-module contract — the media module resolves a neighborhood post
 * as an attachment target through this seam, never through a module
 * dependency (the {@code ListingPriceProvider} house pattern: the
 * interface lives in shared-api, the data owner implements it, the
 * consumer injects it).
 *
 * <p><b>The VISIBLE-only contract</b> is the service's own
 * {@code visiblePost} gate verbatim: an unknown, moderated-hidden or
 * author-deleted post answers the honest 404 — a hidden post cannot gain
 * photos, and Hibernate's {@code @SoftDelete} already hides the deleted
 * from {@code findById} itself. The media write path lands on exactly the
 * post the feed would return.
 *
 * <p><b>The author's community-write right (the #484 review round):</b>
 * the carrier's {@code authorMayWriteCommunity} leg is THIS module's own
 * D-N3 computation — {@code mayUseCommunityWrites()} on the author's
 * ACTIVE membership, with an absent membership answering {@code false}
 * (a non-member holds no community write; the post service's own
 * {@code requireWritableMembership} 403 is the same fact on its side of
 * the boundary). The media line's request and confirm paths gate on it,
 * so a membership rejected AFTER a post was published cannot keep
 * attaching photos to it — the one place the media target's authorization
 * and the author's community-write right genuinely meet.
 */
@Component
@Transactional(readOnly = true)
public class PostLookupAdapter implements PostLookupPort {

    private final NeighborhoodPostRepository postRepository;
    private final NeighborhoodMembershipRepository membershipRepository;

    public PostLookupAdapter(NeighborhoodPostRepository postRepository,
                             NeighborhoodMembershipRepository membershipRepository) {
        this.postRepository = postRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    public PostInfo getPostInfo(UUID postId) {
        return postRepository.findById(postId)
                .filter(post -> post.getStatus() == PostStatus.VISIBLE)
                .map(post -> new PostInfo(post.getId(), post.getAuthorId(),
                        authorMayWriteCommunity(post.getAuthorId())))
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }

    /**
     * The author's CURRENT community-write right — the membership
     * domain's own D-N3 gate, computed at resolution time so every media
     * write step sees the same verdict the post service's own commands
     * see. A REJECTED membership answers {@code false}; so does an absent
     * membership (the author left, or the row never existed — either way
     * there is no community-write right to attach photos with).
     */
    private boolean authorMayWriteCommunity(UUID authorId) {
        return membershipRepository.findByUserId(authorId)
                .map(NeighborhoodMembership::mayUseCommunityWrites)
                .orElse(false);
    }
}
