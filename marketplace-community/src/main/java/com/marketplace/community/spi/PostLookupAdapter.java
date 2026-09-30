package com.marketplace.community.spi;

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
 */
@Component
@Transactional(readOnly = true)
public class PostLookupAdapter implements PostLookupPort {

    private final NeighborhoodPostRepository postRepository;

    public PostLookupAdapter(NeighborhoodPostRepository postRepository) {
        this.postRepository = postRepository;
    }

    @Override
    public PostInfo getPostInfo(UUID postId) {
        return postRepository.findById(postId)
                .filter(post -> post.getStatus() == PostStatus.VISIBLE)
                .map(post -> new PostInfo(post.getId(), post.getAuthorId()))
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }
}
