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
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(keys);
        when(purgeState.markPurged(subject)).thenReturn(3);

        int purged = new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject);

        assertThat(purged).isEqualTo(3);
        InOrder order = inOrder(purgeState, mediaService);
        order.verify(purgeState).markPendingAndCollectKeys(subject);
        order.verify(mediaService).destroyObjectsForErasure(keys);
        order.verify(purgeState).markPurged(subject);
    }

    @Test
    void storageFailureLeavesPendingRowsRetryableAndNeverMarksPurged() {
        UUID subject = UUID.randomUUID();
        List<String> keys = List.of("listings/1/a.jpg");
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(keys);
        org.mockito.Mockito.doThrow(new IllegalStateException("S3 AccessDenied"))
                .when(mediaService).destroyObjectsForErasure(keys);

        assertThrows(IllegalStateException.class,
                () -> new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject));

        verify(purgeState).markPendingAndCollectKeys(subject);
        verify(purgeState, never()).markPurged(subject);
    }

    @Test
    void alreadyAbsentObjectsAreFinalizedWithoutRequiringStorage() {
        UUID subject = UUID.randomUUID();
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(List.of());
        when(purgeState.markPurged(subject)).thenReturn(2);

        int purged = new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject);

        assertThat(purged).isEqualTo(2);
        verify(mediaService, never()).destroyObjectsForErasure(org.mockito.ArgumentMatchers.any());
        verify(purgeState).markPurged(subject);
    }

    @Test
    void retryProcessesPreviouslyPendingKeysAgainBeforeFinalization() {
        UUID subject = UUID.randomUUID();
        List<String> pendingKeys = List.of("posts/2/b.jpg");
        when(purgeState.markPendingAndCollectKeys(subject)).thenReturn(pendingKeys);
        when(purgeState.markPurged(subject)).thenReturn(1);

        new MediaPurgeAdapter(purgeState, mediaService).purgeAuthoredTexts(subject);

        verify(mediaService).destroyObjectsForErasure(pendingKeys);
        verify(purgeState).markPurged(subject);
    }
}
