package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1 §4.4 — the review-photo surface: the same two-phase presign/confirm
 * flow the listing media runs, scoped to a review, with ownership resolved
 * through the reviews port (the module law — no profile table between the
 * caller and the asset) and the read path following the review's own
 * visibility contract.
 */
class ReviewMediaServiceTest {

    private final ReviewMediaRepository reviewMediaRepository = mock(ReviewMediaRepository.class);
    private final S3MediaStorage s3 = mock(S3MediaStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<S3MediaStorage> storage = mock(ObjectProvider.class);
    private final ReviewLookupPort reviewLookupPort = mock(ReviewLookupPort.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final Authentication authentication = mock(Authentication.class);

    private static final MediaProperties PROPERTIES = new MediaProperties(
            new MediaProperties.Storage("https://example.test", "auto", "bucket", "k", "s", false),
            new MediaProperties.Limits(10_485_760L, 10,
                    Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                    Duration.ofMinutes(15), 640, 25_000_000L));

    private ReviewMediaService service;

    @BeforeEach
    void setUp() {
        when(storage.getIfAvailable()).thenReturn(s3);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        service = new ReviewMediaService(reviewMediaRepository, storage, PROPERTIES,
                reviewLookupPort, currentUserProvider);
    }

    private ReviewMedia asset(UUID reviewId, UUID uploaderId, MediaAssetStatus status) {
        ReviewMedia asset = ReviewMedia.create(reviewId, uploaderId,
                "review-media/" + reviewId + "/key.jpg", "image/jpeg", 1024L, 1);
        if (status == MediaAssetStatus.UPLOADED) {
            asset.markUploaded();
        }
        return asset;
    }

    @Test
    void requestUpload_signsAServerGeneratedKey() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(authorId));
        when(reviewMediaRepository.countByReviewId(reviewId)).thenReturn(2L);
        when(reviewMediaRepository.save(any(ReviewMedia.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(s3.presignUpload(anyString(), eq("image/jpeg"))).thenReturn("https://upload.test");

        ReviewMediaService.ReviewMediaUploadView view =
                service.requestUpload(reviewId, "IMAGE/JPEG", 1024L, authentication);

        assertTrue(view.objectKey().startsWith("review-media/" + reviewId + "/"));
        assertTrue(view.objectKey().endsWith(".jpg"));
        assertEquals("https://upload.test", view.uploadUrl());
        assertEquals(Duration.ofMinutes(15), view.urlLifetime());
        // the position serializes per review (the #241 advisory-lock shape)
        verify(reviewMediaRepository).lockPositionAllocation(reviewId.toString());
    }

    @Test
    void requestUpload_rejectsAnUnknownReview() {
        UUID reviewId = UUID.randomUUID();
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.empty());

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> service.requestUpload(reviewId, "image/jpeg", 1024L, authentication));

        assertTrue(thrown.getMessage().contains(reviewId.toString()));
    }

    @Test
    void requestUpload_deniesACallerWhoIsNotTheAuthor() {
        UUID reviewId = UUID.randomUUID();
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThrows(AccessDeniedException.class,
                () -> service.requestUpload(reviewId, "image/jpeg", 1024L, authentication));
    }

    @Test
    void requestUpload_letsAnAdminThrough() {
        UUID reviewId = UUID.randomUUID();
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(UUID.randomUUID()));
        when(reviewMediaRepository.countByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(s3.presignUpload(anyString(), anyString())).thenReturn("https://upload.test");

        assertTrue(service.requestUpload(reviewId, "image/jpeg", 1024L, authentication).uploadUrl()
                .equals("https://upload.test"));
    }

    @Test
    void requestUpload_rejectsATypeOutsideTheAllowlist() {
        UUID reviewId = UUID.randomUUID();

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> service.requestUpload(reviewId, "application/pdf", 1024L, authentication));

        assertTrue(thrown.getMessage().contains("application/pdf"));
    }

    @Test
    void requestUpload_rejectsASizeOutsideTheBound() {
        UUID reviewId = UUID.randomUUID();

        assertThrows(BadRequestException.class,
                () -> service.requestUpload(reviewId, "image/jpeg", 0L, authentication));
        assertThrows(BadRequestException.class,
                () -> service.requestUpload(reviewId, "image/jpeg", 999_999_999L, authentication));
    }

    @Test
    void requestUpload_rejectsABlankContentType() {
        UUID reviewId = UUID.randomUUID();

        assertThrows(BadRequestException.class,
                () -> service.requestUpload(reviewId, "  ", 1024L, authentication));
    }

    @Test
    void confirmUpload_verifiesTheObjectThenPublishes() {
        UUID reviewId = UUID.randomUUID();
        UUID uploaderId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(uploaderId);
        ReviewMedia asset = asset(reviewId, uploaderId, MediaAssetStatus.PENDING_UPLOAD);
        when(reviewMediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(s3.verifyUploaded(anyString(), eq("image/jpeg"), anyLong())).thenReturn(true);
        when(s3.presignDownload(anyString())).thenReturn("https://download.test");

        ReviewMediaService.ReviewMediaView view = service.confirmUpload(asset.getId(), authentication);

        assertEquals("UPLOADED", view.status());
        assertEquals("https://download.test", view.downloadUrl());
        assertEquals(MediaAssetStatus.UPLOADED, asset.getStatus());
    }

    @Test
    void confirmUpload_rejectsAnObjectStorageDoesNotHave() {
        UUID uploaderId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(uploaderId);
        ReviewMedia asset = asset(UUID.randomUUID(), uploaderId, MediaAssetStatus.PENDING_UPLOAD);
        when(reviewMediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(s3.verifyUploaded(anyString(), anyString(), anyLong())).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> service.confirmUpload(asset.getId(), authentication));
        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
    }

    @Test
    void confirmUpload_rejectsAnUnknownAsset() {
        UUID mediaId = UUID.randomUUID();
        when(reviewMediaRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.confirmUpload(mediaId, authentication));
    }

    @Test
    void confirmUpload_deniesACallerWhoIsNotTheUploader() {
        ReviewMedia asset = asset(UUID.randomUUID(), UUID.randomUUID(), MediaAssetStatus.PENDING_UPLOAD);
        when(reviewMediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class,
                () -> service.confirmUpload(asset.getId(), authentication));
    }

    @Test
    void listByReview_isPublicForAPublishedReview() {
        UUID reviewId = UUID.randomUUID();
        when(reviewLookupPort.findVisibleAuthorId(reviewId)).thenReturn(Optional.of(UUID.randomUUID()));
        ReviewMedia asset = asset(reviewId, UUID.randomUUID(), MediaAssetStatus.UPLOADED);
        when(reviewMediaRepository.findByReviewIdAndStatusOrderByPositionAsc(
                reviewId, MediaAssetStatus.UPLOADED)).thenReturn(List.of(asset));
        when(s3.presignDownload(anyString())).thenReturn("https://download.test");

        List<ReviewMediaService.ReviewMediaView> views = service.listByReview(reviewId, null);

        assertEquals(1, views.size());
        assertEquals(asset.getId(), views.get(0).id());
        verify(reviewLookupPort, never()).findAuthorId(reviewId);
    }

    @Test
    void listByReview_hidesANonPublishedReviewFromEveryoneElse() {
        UUID reviewId = UUID.randomUUID();
        when(reviewLookupPort.findVisibleAuthorId(reviewId)).thenReturn(Optional.empty());
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(UUID.randomUUID()));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.tryGetCurrentUserId(authentication)).thenReturn(Optional.empty());
        when(reviewMediaRepository.findByReviewIdAndStatusOrderByPositionAsc(
                reviewId, MediaAssetStatus.UPLOADED)).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class,
                () -> service.listByReview(reviewId, authentication));
    }

    @Test
    void listByReview_letsTheAuthorSeeTheirOwnPendingReview() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(reviewLookupPort.findVisibleAuthorId(reviewId)).thenReturn(Optional.empty());
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(authorId));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.tryGetCurrentUserId(authentication)).thenReturn(Optional.of(authorId));
        when(reviewMediaRepository.findByReviewIdAndStatusOrderByPositionAsc(
                reviewId, MediaAssetStatus.UPLOADED)).thenReturn(List.of());

        assertTrue(service.listByReview(reviewId, authentication).isEmpty());
    }

    @Test
    void listByReview_rejectsAnUnknownReview() {
        UUID reviewId = UUID.randomUUID();
        when(reviewLookupPort.findVisibleAuthorId(reviewId)).thenReturn(Optional.empty());
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.listByReview(reviewId, authentication));
    }

    @Test
    void delete_removesTheRowAndTheStorageObject() {
        UUID uploaderId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(uploaderId);
        ReviewMedia asset = asset(UUID.randomUUID(), uploaderId, MediaAssetStatus.UPLOADED);
        when(reviewMediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        service.delete(asset.getId(), authentication);

        verify(reviewMediaRepository).delete(asset);
        verify(s3).deleteObject(asset.getObjectKey());
    }

    @Test
    void delete_survivesAStorageFailure() {
        UUID uploaderId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(uploaderId);
        ReviewMedia asset = asset(UUID.randomUUID(), uploaderId, MediaAssetStatus.UPLOADED);
        when(reviewMediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        doThrow(new RuntimeException("bucket unreachable")).when(s3).deleteObject(anyString());

        service.delete(asset.getId(), authentication);

        verify(reviewMediaRepository).delete(asset);
    }

    @Test
    void delete_deniesACallerWhoIsNotTheUploader() {
        ReviewMedia asset = asset(UUID.randomUUID(), UUID.randomUUID(), MediaAssetStatus.UPLOADED);
        when(reviewMediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class,
                () -> service.delete(asset.getId(), authentication));
        verify(reviewMediaRepository, never()).delete(any(ReviewMedia.class));
    }
    /**
     * CodeRabbit W1 r4 (adopted from the root): the per-review upload limit —
     * the count check runs while the advisory lock is held, so this is the
     * anti-abuse contract of the channel, not a courtesy.
     */
    @Test
    void requestUpload_rejectsAtThePerReviewLimit() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(authorId));
        when(reviewMediaRepository.countByReviewId(reviewId)).thenReturn(10L);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.requestUpload(reviewId, "image/jpeg", 1024L, authentication));
        assertTrue(ex.getMessage().contains("at most 10"));
    }

    /**
     * Greptile W1 r10 (adopted from the root): the allocated position follows
     * the highest allocated one, not the live count — a soft deletion never
     * re-opens a slot a remaining row still holds.
     */
    @Test
    void requestUpload_allocatesAfterTheHighestPositionNotTheCount() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(authorId);
        when(reviewLookupPort.findAuthorId(reviewId)).thenReturn(Optional.of(authorId));
        when(reviewMediaRepository.countByReviewId(reviewId)).thenReturn(2L);
        when(reviewMediaRepository.findMaxPositionByReviewId(reviewId)).thenReturn(5);
        when(reviewMediaRepository.save(any(ReviewMedia.class)))
                .thenAnswer(call -> call.getArgument(0));

        service.requestUpload(reviewId, "image/jpeg", 1024L, authentication);
        org.mockito.ArgumentCaptor<ReviewMedia> captor =
                org.mockito.ArgumentCaptor.forClass(ReviewMedia.class);
        verify(reviewMediaRepository).save(captor.capture());
        assertEquals(6, captor.getValue().getPosition());
    }
}
