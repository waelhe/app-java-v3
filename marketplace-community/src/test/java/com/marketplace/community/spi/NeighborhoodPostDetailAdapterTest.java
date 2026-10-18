package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodPostService;
import com.marketplace.community.NeighborhoodPostView;
import com.marketplace.community.PostCommentView;
import com.marketplace.community.PostMediaView;
import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.NeighborhoodPostCommentView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 (the composite post/source detail journey): the cross-module
 * detail seam, unit-pinned — the adapter owns NO rule of its own (the
 * inventory #599 §8: REST and GraphQL invoke ONE use case and ONE set of
 * rules): both legs delegate to the SAME use cases the REST surface's
 * controller invokes ({@code getPost}/{@code getComments}), the owner's
 * denials travel the seam unchanged (the honest 404 and the membership
 * 403 — the GraphQL resolver classifies them at the field level, never
 * the adapter), the pagination vocabulary translates through the
 * documented {@link SpringPagination} interop corner (the neutral
 * request → the owner's {@code Pageable}, the owner's complete sort key
 * pinned), and the mappings into the shared transport projections are
 * mechanical and total (field-for-field, none invented, none dropped).
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodPostDetailAdapterTest {

    @Mock
    private NeighborhoodPostService postService;

    @InjectMocks
    private NeighborhoodPostDetailAdapter adapter;

    private final UUID callerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();

    private NeighborhoodPostView detailView() {
        return new NeighborhoodPostView(
                postId,
                authorId,
                locationId,
                "GENERAL",
                "Title",
                "Body",
                "VISIBLE",
                false,
                null,
                4L,
                true,
                List.of(new PostMediaView(
                        UUID.randomUUID(), "https://presigned/one", null, "image/jpeg", 0)),
                Instant.parse("2026-10-18T09:00:00Z"),
                Instant.parse("2026-10-18T09:00:00Z"));
    }

    private PostCommentView commentView(int ordinal) {
        return new PostCommentView(
                UUID.nameUUIDFromBytes(("comment-" + ordinal).getBytes()),
                postId,
                UUID.nameUUIDFromBytes(("commenter-" + ordinal).getBytes()),
                "Comment " + ordinal,
                Instant.parse("2026-10-18T10:00:00Z").plusSeconds(ordinal),
                Instant.parse("2026-10-18T10:00:00Z").plusSeconds(ordinal));
    }

    // ---------- the detail leg ----------

    @Test
    void post_delegatesToTheSameUseCaseTheRestSurfaceInvokes() {
        NeighborhoodPostView view = detailView();
        when(postService.getPost(callerId, postId)).thenReturn(view);

        NeighborhoodFeedPostView shared = adapter.post(callerId, postId);

        // The mapping is mechanical and total: every field of the
        // same-journey set crosses, none invented, none dropped.
        assertThat(shared.id()).isEqualTo(postId);
        assertThat(shared.authorId()).isEqualTo(authorId);
        assertThat(shared.locationId()).isEqualTo(locationId);
        assertThat(shared.category()).isEqualTo("GENERAL");
        assertThat(shared.title()).isEqualTo("Title");
        assertThat(shared.body()).isEqualTo("Body");
        assertThat(shared.status()).isEqualTo("VISIBLE");
        assertThat(shared.declaredCommercial()).isFalse();
        assertThat(shared.lostFoundState()).isNull();
        assertThat(shared.reactionsCount()).isEqualTo(4L);
        assertThat(shared.reactedByMe()).isTrue();
        assertThat(shared.media()).hasSize(1);
        assertThat(shared.media().getFirst().url()).isEqualTo("https://presigned/one");
        assertThat(shared.createdAt()).isEqualTo(view.createdAt());
        assertThat(shared.updatedAt()).isEqualTo(view.updatedAt());

        verify(postService).getPost(callerId, postId);
    }

    @Test
    void post_theOwnersHonest404TravelsTheSeamUnchanged() {
        when(postService.getPost(callerId, postId))
                .thenThrow(new ResourceNotFoundException("Post", postId));

        assertThatThrownBy(() -> adapter.post(callerId, postId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void post_theOwnersMembership403TravelsTheSeamUnchanged() {
        when(postService.getPost(callerId, postId)).thenThrow(
                new AccessDeniedException("Only members of the post's neighborhood can read it"));

        assertThatThrownBy(() -> adapter.post(callerId, postId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only members of the post's neighborhood can read it");
    }

    // ---------- the comments leg ----------

    @Test
    void comments_delegatesToTheSameUseCaseTheRestSurfaceInvokes() {
        when(postService.getComments(eq(callerId), eq(postId), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(
                        List.of(commentView(0), commentView(1)),
                        inv.getArgument(2), 5));

        PagedResponse<NeighborhoodPostCommentView> page =
                adapter.comments(callerId, postId, PagedRequest.of(0, 2));

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(5L);
        assertThat(page.content().get(0).id()).isEqualTo(commentView(0).id());
        assertThat(page.content().get(0).postId()).isEqualTo(postId);
        assertThat(page.content().get(0).authorId()).isEqualTo(commentView(0).authorId());
        assertThat(page.content().get(0).body()).isEqualTo("Comment 0");
        assertThat(page.content().get(0).createdAt()).isEqualTo(commentView(0).createdAt());
        assertThat(page.content().get(1).body()).isEqualTo("Comment 1");
    }

    @Test
    void comments_translatesTheNeutralRequestIntoTheOwnersPageable() {
        when(postService.getComments(eq(callerId), eq(postId), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(2), 0));

        adapter.comments(callerId, postId, PagedRequest.of(3, 25));

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(postService).getComments(eq(callerId), eq(postId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        // The neutral request arrives unsorted by contract and crosses as
        // unsorted — the owner's complete sort key (createdAt ASC, id ASC)
        // is pinned inside the use case (the service test's own pin), never
        // the adapter's vocabulary.
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
    }

    @Test
    void comments_theOwnersDenialsTravelTheSeamUnchanged() {
        when(postService.getComments(eq(callerId), eq(postId), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException("Post", postId));

        assertThatThrownBy(() -> adapter.comments(callerId, postId, PagedRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(postService.getComments(eq(callerId), eq(postId), any(Pageable.class)))
                .thenThrow(new AccessDeniedException(
                        "Join a neighborhood before reading comments (PUT /api/v1/me/neighborhood)"));

        assertThatThrownBy(() -> adapter.comments(callerId, postId, PagedRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
