package com.marketplace.community;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L42 — the feed surface's delegation contract: the caller's user id
 * rides the CurrentUserProvider seam, the category type gate parses
 * BEFORE any service call (criterion 3), the writes answer 201 and the
 * author delete answers 204. The HTTP validation shape (400 blank
 * title/body) and the security shape (401 anonymous) are pinned by the
 * WebMvc and integration tests on the real chain.
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodPostControllerTest {

    @Mock
    private NeighborhoodPostService postService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private NeighborhoodPostController controller;

    private UUID authorId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID postId = UUID.randomUUID();

    private NeighborhoodPostView view() {
        return new NeighborhoodPostView(postId, authorId, locationId,
                "GENERAL", "Title", "Body", "VISIBLE",
                Instant.parse("2026-09-17T09:30:00Z"),
                Instant.parse("2026-09-17T09:30:00Z"));
    }

    @Test
    void feed_delegatesWithTheParsedCategory() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(postService.getFeed(org.mockito.ArgumentMatchers.eq(authorId),
                org.mockito.ArgumentMatchers.eq(PostCategory.CLASSIFIED),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.feed("CLASSIFIED", Pageable.unpaged(), authentication);

        verify(postService).getFeed(org.mockito.ArgumentMatchers.eq(authorId),
                org.mockito.ArgumentMatchers.eq(PostCategory.CLASSIFIED),
                org.mockito.ArgumentMatchers.any(Pageable.class));
    }

    @Test
    void feed_absentCategory_delegatesWithNull() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(postService.getFeed(org.mockito.ArgumentMatchers.eq(authorId),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.feed(null, Pageable.unpaged(), authentication);

        verify(postService).getFeed(org.mockito.ArgumentMatchers.eq(authorId),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(Pageable.class));
    }

    @Test
    void feed_invalidCategory_is400BeforeAnyServiceCall() {
        com.marketplace.shared.api.BadRequestException thrown = org.assertj.core.api.Assertions
                .catchThrowableOfType(() -> controller.feed("NOT_A_CATEGORY",
                        Pageable.unpaged(), authentication),
                        com.marketplace.shared.api.BadRequestException.class);

        // L43 widened the listed vocabulary to the four values.
        assertThat(thrown).hasMessageContaining(
                "GENERAL, CLASSIFIED, LOST_FOUND, RECOMMENDATION");
        org.mockito.Mockito.verifyNoInteractions(postService);
    }

    @Test
    void create_answers201WithTheStoredView() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(postService.createPost(authorId, locationId, PostCategory.GENERAL,
                "Title", "Body")).thenReturn(view());

        ResponseEntity<NeighborhoodPostView> result = controller.create(
                new NeighborhoodPostController.CreatePostRequest(
                        locationId, "GENERAL", "Title", "Body"),
                authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().id()).isEqualTo(postId);
    }

    @Test
    void create_invalidCategory_is400BeforeAnyServiceCall() {
        assertThatThrownBy(() -> controller.create(
                new NeighborhoodPostController.CreatePostRequest(
                        locationId, "SPAM", "Title", "Body"),
                authentication))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        org.mockito.Mockito.verifyNoInteractions(postService);
    }

    @Test
    void comment_answers201WithTheStoredView() {
        UUID commenterId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(commenterId);
        PostCommentView commentView = new PostCommentView(
                UUID.randomUUID(), postId, commenterId, "Nice",
                Instant.parse("2026-09-17T09:30:00Z"),
                Instant.parse("2026-09-17T09:30:00Z"));
        when(postService.comment(commenterId, postId, "Nice")).thenReturn(commentView);

        ResponseEntity<PostCommentView> result = controller.comment(postId,
                new NeighborhoodPostController.CreateCommentRequest("Nice"), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().postId()).isEqualTo(postId);
    }

    @Test
    void comments_delegatesToTheService() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(postService.getComments(org.mockito.ArgumentMatchers.eq(authorId),
                org.mockito.ArgumentMatchers.eq(postId),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        controller.comments(postId, Pageable.unpaged(), authentication);

        verify(postService).getComments(org.mockito.ArgumentMatchers.eq(authorId),
                org.mockito.ArgumentMatchers.eq(postId),
                org.mockito.ArgumentMatchers.any(Pageable.class));
    }

    @Test
    void delete_answers204() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);

        ResponseEntity<Void> result = controller.delete(postId, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(postService).deleteByAuthor(authorId, postId);
    }
}
