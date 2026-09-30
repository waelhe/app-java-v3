package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodPost;
import com.marketplace.community.NeighborhoodPostRepository;
import com.marketplace.community.PostCategory;
import com.marketplace.community.PostStatus;
import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * L48 (gap #2 — post images): the media module's post-resolution seam,
 * unit-pinned — the VISIBLE-only contract is the service's own
 * {@code visiblePost} gate verbatim: an unknown, moderated-hidden or
 * author-deleted post answers the honest 404 (the soft-deleted never
 * even reaches the filter — Hibernate's {@code @SoftDelete} hides them
 * from {@code findById} itself), and a VISIBLE post resolves to exactly
 * its id and its author (no JPA relation crosses the boundary — the
 * port's plain-UUID carrier).
 */
@ExtendWith(MockitoExtension.class)
class PostLookupAdapterTest {

    private static final Instant FIXED = Instant.parse("2026-09-30T12:00:00Z");

    @Mock
    private NeighborhoodPostRepository postRepository;

    @InjectMocks
    private PostLookupAdapter adapter;

    private final UUID postId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();

    private NeighborhoodPost post() {
        return NeighborhoodPost.post(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body",
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void visiblePost_resolvesIdAndAuthor() {
        NeighborhoodPost post = post();
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

        PostLookupPort.PostInfo info = adapter.getPostInfo(post.getId());

        assertThat(info.postId()).isEqualTo(post.getId());
        assertThat(info.authorId()).isEqualTo(authorId);
    }

    @Test
    void unknownPost_answersTheHonest404() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.getPostInfo(postId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void hiddenPost_answersTheHonest404() {
        // A moderated-hidden post cannot gain photos: the media write path
        // lands on exactly the post the feed would return. The hide flip is
        // the moderation package's own single write — the entity stub pins
        // the state the adapter must refuse.
        NeighborhoodPost hidden = org.mockito.Mockito.mock(NeighborhoodPost.class);
        when(hidden.getStatus()).thenReturn(PostStatus.HIDDEN_BY_MODERATOR);
        when(postRepository.findById(postId)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> adapter.getPostInfo(postId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletedPost_neverResolves() {
        // Author-deleted posts are invisible to findById itself (the shared
        // @SoftDelete filter) — the same 404 as an unknown post.
        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.getPostInfo(postId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
