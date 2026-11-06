package com.marketplace.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 2026-11-06 #809 round (Major · Functional Correctness): the abandoned
 * pending-upload reaper's unit proof — the JUnit 5 + Mockito style the
 * module's own tests established (no Spring context: the scheduling and
 * transaction semantics are integration concerns; the reaping DECISIONS
 * are proven HERE).
 *
 * <p>Coverage target: the horizon (only rows older than presignTtl plus
 * the grace are reaped), the un-confirmability (the terminal EXPIRED
 * transition), the best-effort object deletion (an absent storage skips
 * the deletion but still expires the row; a FAILING deletion keeps the
 * row PENDING for the next pass), and the boundary discipline (UPLOADED
 * rows are never the feed).</p>
 */
class PendingUploadCleanupJobTest {

    private MediaAssetRepository repository;
    private S3MediaStorage s3;
    private MediaProperties properties;
    private PendingUploadCleanupJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(MediaAssetRepository.class);
        s3 = mock(S3MediaStorage.class);
        properties = mock(MediaProperties.class);
        when(properties.limits()).thenReturn(new MediaProperties.Limits(
                10_485_760L, 10, 200,
                java.util.Set.of("image/jpeg", "image/png"), Duration.ofMinutes(15), 640, 25_000_000L));
        ObjectProvider<S3MediaStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(s3);
        job = new PendingUploadCleanupJob(repository, provider, properties, 24L);
    }

    private static MediaAsset pendingOlderThan(Duration age) {
        MediaAsset asset = MediaAsset.createForProduct(
                UUID.randomUUID(), UUID.randomUUID(), "media/products/x/img.jpeg",
                "image/jpeg", 1024L, 1);
        org.springframework.test.util.ReflectionTestUtils.setField(asset, "createdAt", Instant.now().minus(age));
        return asset;
    }

    @Test
    void anAbandonedPendingUploadPastTheHorizonIsExpiredAndItsObjectDeleted() {
        MediaAsset abandoned = pendingOlderThan(Duration.ofHours(40));
        when(repository.findByStatusAndCreatedAtBefore(
                any(MediaAssetStatus.class), any(Instant.class))).thenReturn(List.of(abandoned));

        job.reapAbandonedPendingUploads();

        assertThat(abandoned.getStatus()).isEqualTo(MediaAssetStatus.EXPIRED);
        verify(s3).deleteObject("media/products/x/img.jpeg");
    }

    @Test
    void aPendingUploadInsideTheGraceWindowIsNeverFedToTheJob() {
        // The horizon is the repository query's own cutoff (presignTtl +
        // grace); the job itself reaps exactly what it is fed — the proof
        // pins the feed's status and the query's shape.
        when(repository.findByStatusAndCreatedAtBefore(
                any(MediaAssetStatus.class), any(Instant.class))).thenReturn(List.of());

        job.reapAbandonedPendingUploads();

        verify(repository).findByStatusAndCreatedAtBefore(MediaAssetStatus.PENDING_UPLOAD,
                org.mockito.ArgumentMatchers.argThat(cutoff ->
                        java.time.Duration.between(cutoff, Instant.now()).toHours() >= 24));
        verify(s3, never()).deleteObject(anyString());
    }

    @Test
    void aStorageFailureKeepsTheRowPendingForTheNextPass() {
        // Best-effort deletion: an un-deleted object must never leak past a
        // live row that could still be confirmed — the row stays PENDING.
        MediaAsset abandoned = pendingOlderThan(Duration.ofHours(40));
        when(repository.findByStatusAndCreatedAtBefore(
                any(MediaAssetStatus.class), any(Instant.class))).thenReturn(List.of(abandoned));
        org.mockito.Mockito.doThrow(new RuntimeException("storage outage"))
                .when(s3).deleteObject(anyString());

        job.reapAbandonedPendingUploads();

        assertThat(abandoned.getStatus()).isEqualTo(MediaAssetStatus.PENDING_UPLOAD);
    }

    @Test
    void anAbsentStorageStillExpiresTheAbandonedRow() {
        // No storage configured (the ObjectProvider's empty case): the
        // object cannot be deleted, but the row is still expired — the
        // slot's lifecycle must not depend on the deletion succeeding.
        MediaAsset abandoned = pendingOlderThan(Duration.ofHours(40));
        when(repository.findByStatusAndCreatedAtBefore(
                any(MediaAssetStatus.class), any(Instant.class))).thenReturn(List.of(abandoned));
        @SuppressWarnings("unchecked")
        ObjectProvider<S3MediaStorage> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        new PendingUploadCleanupJob(repository, empty, properties, 24L).reapAbandonedPendingUploads();

        assertThat(abandoned.getStatus()).isEqualTo(MediaAssetStatus.EXPIRED);
    }

    @Test
    void anExpiredRowCanNeverBeConfirmedAgain() {
        // The un-confirmability itself: the state machine refuses the
        // EXPIRED -> UPLOADED transition (confirmUpload's gate).
        MediaAsset abandoned = pendingOlderThan(Duration.ofHours(40));
        when(repository.findByStatusAndCreatedAtBefore(
                any(MediaAssetStatus.class), any(Instant.class))).thenReturn(List.of(abandoned));

        job.reapAbandonedPendingUploads();

        org.assertj.core.api.Assertions.assertThatThrownBy(abandoned::markUploaded)
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class);
    }
}
