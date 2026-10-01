package com.marketplace.community.spi;

import com.marketplace.community.MembershipVerificationState;
import com.marketplace.community.NeighborhoodMembership;
import com.marketplace.community.NeighborhoodMembershipRepository;
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
 * its id, its author, and the author's CURRENT community-write right (no
 * JPA relation crosses the boundary — the port's plain-UUID carrier).
 *
 * <p><b>The #484 review round:</b> the carrier's
 * {@code authorMayWriteCommunity} leg is the membership domain's own
 * D-N3 verdict — every verification state except REJECTED passes, an
 * absent membership fails. The media line's write paths gate on it, so a
 * member rejected after publishing cannot keep attaching photos.
 */
@ExtendWith(MockitoExtension.class)
class PostLookupAdapterTest {

    private static final Instant FIXED = Instant.parse("2026-09-30T12:00:00Z");

    @Mock
    private NeighborhoodPostRepository postRepository;

    @Mock
    private NeighborhoodMembershipRepository membershipRepository;

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

    private NeighborhoodMembership membershipInState(MembershipVerificationState state) {
        return membershipInState(state, locationId);
    }

    private NeighborhoodMembership membershipInState(MembershipVerificationState state, UUID membershipLocation) {
        // state is produced through the entity's own lifecycle below — the
        // factory + transitions are the ONLY honest paths to each state.
        NeighborhoodMembership membership =
                NeighborhoodMembership.join(authorId, membershipLocation, Clock.fixed(FIXED, ZoneOffset.UTC));
        if (state != MembershipVerificationState.UNVERIFIED) {
            membership.requestVerification();
        }
        if (state == MembershipVerificationState.REJECTED) {
            membership.rejectVerification();
        }
        if (state == MembershipVerificationState.VERIFIED) {
            membership.approveVerification();
        }
        return membership;
    }

    @Test
    void visiblePost_resolvesIdAuthorAndTheWriteRight() {
        NeighborhoodPost post = post();
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipInState(MembershipVerificationState.UNVERIFIED)));

        PostLookupPort.PostInfo info = adapter.getPostInfo(post.getId());

        assertThat(info.postId()).isEqualTo(post.getId());
        assertThat(info.authorId()).isEqualTo(authorId);
        assertThat(info.authorMayWriteCommunity()).isTrue();
    }

    @Test
    void visiblePost_byRejectedAuthor_carriesTheRefusedWriteRight() {
        NeighborhoodPost post = post();
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipInState(MembershipVerificationState.REJECTED)));

        PostLookupPort.PostInfo info = adapter.getPostInfo(post.getId());

        assertThat(info.authorMayWriteCommunity()).isFalse();
    }

    @Test
    void visiblePost_byAuthorWhoSwitchedNeighborhood_carriesTheRefusedWriteRight() {
        // The review round's location leg: an author whose ACTIVE membership
        // moved to another neighborhood holds no write right in the post's
        // own neighborhood — the photo gate must see exactly what the post
        // service's requireWritableMembershipIn would answer for a comment
        // or reaction on that same post.
        NeighborhoodPost post = post();
        UUID otherNeighborhood = UUID.randomUUID();
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipInState(
                        MembershipVerificationState.UNVERIFIED, otherNeighborhood)));

        PostLookupPort.PostInfo info = adapter.getPostInfo(post.getId());

        assertThat(info.authorMayWriteCommunity())
                .as("a membership in ANOTHER neighborhood is not a write right in this one")
                .isFalse();
    }

    @Test
    void visiblePost_byAuthorWithoutMembership_carriesTheRefusedWriteRight() {
        // The author left (or the row never existed) — there is no
        // community-write right to attach photos with; the media line's
        // gate sees the same refusal the post service's own
        // requireWritableMembership would answer.
        NeighborhoodPost post = post();
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.empty());

        PostLookupPort.PostInfo info = adapter.getPostInfo(post.getId());

        assertThat(info.authorMayWriteCommunity()).isFalse();
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
