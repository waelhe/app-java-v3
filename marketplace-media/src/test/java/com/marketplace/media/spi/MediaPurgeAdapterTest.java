package com.marketplace.media.spi;

import com.marketplace.media.MediaPurgeStateService;
import com.marketplace.media.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G3 adapter orchestration: pending state commits, storage succeeds, then final state commits. */
@ExtendWith(MockitoExtension.class)
class MediaPurgeAdapterTest {

    @Mock
    private MediaPurgeStateService purgeState;

    @Mock
    private MediaService mediaService;

    @Test
    void commitsPendingStateDeletesExactKeysThenFinalizesPurgedRows() {
        UUID subject = UUID.randomUUID();
        List<String> keys = List.of(
                "listings/1/a.jpg", "listings/1/a.jpg/thumb", "review-media/3/c.jpg");
        var batch = new MediaPurgeStateService.PurgeBatch(
                keys, List.of(UUID.randomUUID(), UUID.randomUUID()), List.of(UUID.randomUUID()));
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(batch);
        when(purgeState.markPurged(subject, batch)).thenReturn(3);

        int purged = new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject);

        assertThat(purged).isEqualTo(3);
        InOrder order = inOrder(purgeState, mediaService);
        order.verify(purgeState).markPendingAndCollectKeys(subject);
        order.verify(mediaService).destroyObjectsForErasure(keys);
        order.verify(purgeState).markPurged(subject, batch);
    }

    @Test
    void storageFailureLeavesPendingRowsRetryableAndNeverMarksPurged() {
        UUID subject = UUID.randomUUID();
        List<String> keys = List.of("listings/1/a.jpg");
        var batch = new MediaPurgeStateService.PurgeBatch(
                keys, List.of(UUID.randomUUID()), List.of());
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(batch);
        org.mockito.Mockito.doThrow(new IllegalStateException("S3 AccessDenied"))
                .when(mediaService).destroyObjectsForErasure(keys);

        assertThrows(IllegalStateException.class,
                () -> new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject));

        verify(purgeState).markPendingAndCollectKeys(subject);
        verify(purgeState, never()).markPurged(subject, batch);
    }

    @Test
    void alreadyAbsentObjectsAreFinalizedWithoutRequiringStorage() {
        UUID subject = UUID.randomUUID();
        var batch = new MediaPurgeStateService.PurgeBatch(
                List.of(), List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(batch);
        when(purgeState.markPurged(subject, batch)).thenReturn(2);

        int purged = new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject);

        assertThat(purged).isEqualTo(2);
        verify(mediaService, never()).destroyObjectsForErasure(org.mockito.ArgumentMatchers.any());
        verify(purgeState).markPurged(subject, batch);
    }

    @Test
    void retryProcessesPreviouslyPendingKeysAgainBeforeFinalization() {
        UUID subject = UUID.randomUUID();
        List<String> pendingKeys = List.of("posts/2/b.jpg");
        var batch = new MediaPurgeStateService.PurgeBatch(
                pendingKeys, List.of(UUID.randomUUID()), List.of());
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(batch);
        when(purgeState.markPurged(subject, batch)).thenReturn(1);

        new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject);

        verify(mediaService).destroyObjectsForErasure(pendingKeys);
        verify(purgeState).markPurged(subject, batch);
    }
}
