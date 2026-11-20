package com.marketplace.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G3 state contract: access is blocked in a committed, retryable state before
 * storage I/O; only rows captured by a successful storage batch can finalize.
 */
@ExtendWith(MockitoExtension.class)
class MediaPurgeStateServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void markPendingCollectsOriginalsThumbnailsAndReviewMediaBeforeSettingRetryableState() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID assetOne = UUID.randomUUID();
        UUID assetTwo = UUID.randomUUID();
        UUID reviewMediaId = UUID.randomUUID();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowCallbackHandler handler = invocation.getArgument(1);
            if (sql.contains("pg_advisory_xact_lock")) return null;
            assertThat(sql).contains("FOR UPDATE");
            ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
            if (sql.contains("media_assets")) {
                when(row.getObject(1)).thenReturn(assetOne, assetTwo);
                when(row.getString(2)).thenReturn("listings/1/a.jpg", "posts/2/b.jpg");
                when(row.getString(3)).thenReturn("listings/1/a.jpg/thumb").thenReturn((String) null);
                when(row.getString(4)).thenReturn("media-upload-staging/asset-a").thenReturn((String) null);
                handler.processRow(row);
                handler.processRow(row);
            } else {
                when(row.getObject(1)).thenReturn(reviewMediaId);
                when(row.getString(2)).thenReturn("review-media/3/c.jpg");
                when(row.getString(3)).thenReturn("media-upload-staging/review-c");
                handler.processRow(row);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq(userId));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2, 1);

        var batch = new MediaPurgeStateService(jdbcTemplate).markPendingAndCollectKeys(userId);

        assertThat(batch.objectKeys()).containsExactly(
                "listings/1/a.jpg", "listings/1/a.jpg/thumb", "media-upload-staging/asset-a",
                "posts/2/b.jpg", "review-media/3/c.jpg", "media-upload-staging/review-c");
        assertThat(batch.mediaAssetIds()).containsExactly(assetOne, assetTwo);
        assertThat(batch.reviewMediaIds()).containsExactly(reviewMediaId);
        verify(jdbcTemplate).update(
                eq("UPDATE media_assets SET status = 'PURGE_PENDING', version = version + 1 "
                        + "WHERE provider_id = ? AND id IN (?,?) AND status NOT IN ('PURGED', 'PURGE_PENDING')"),
                aryEq(new Object[]{userId, assetOne, assetTwo}));
        verify(jdbcTemplate).update(
                eq("UPDATE review_media SET status = 'PURGE_PENDING', version = version + 1 "
                        + "WHERE uploader_id = ? AND id IN (?) AND status NOT IN ('PURGED', 'PURGE_PENDING')"),
                aryEq(new Object[]{userId, reviewMediaId}));
    }

    @Test
    void markPendingIncludesPreviouslyPendingRowsSoFailedDeletionCanBeRetried() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID reviewMediaId = UUID.randomUUID();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("pg_advisory_xact_lock")) return null;
            assertThat(sql).contains("status <> 'PURGED'", "FOR UPDATE");
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
            if (sql.contains("media_assets")) {
                when(row.getObject(1)).thenReturn(assetId);
                when(row.getString(2)).thenReturn("listings/retry.jpg");
                when(row.getString(3)).thenReturn((String) null);
                when(row.getString(4)).thenReturn("media-upload-staging/retry-asset");
            } else {
                when(row.getObject(1)).thenReturn(reviewMediaId);
                when(row.getString(2)).thenReturn("review-media/retry.jpg");
                when(row.getString(3)).thenReturn("media-upload-staging/retry-review");
            }
            handler.processRow(row);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq(userId));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0, 0);

        var batch = new MediaPurgeStateService(jdbcTemplate).markPendingAndCollectKeys(userId);

        assertThat(batch.objectKeys()).containsExactly("listings/retry.jpg", "media-upload-staging/retry-asset",
                "review-media/retry.jpg", "media-upload-staging/retry-review");
        assertThat(batch.mediaAssetIds()).containsExactly(assetId);
        assertThat(batch.reviewMediaIds()).containsExactly(reviewMediaId);
    }

    @Test
    void markPurgedFinalizesOnlyRowsCapturedByThisPurgeBatch() {
        UUID userId = UUID.randomUUID();
        UUID assetOne = UUID.randomUUID();
        UUID assetTwo = UUID.randomUUID();
        UUID reviewMediaId = UUID.randomUUID();
        var batch = new MediaPurgeStateService.PurgeBatch(
                List.of("listings/1/a.jpg", "media-upload-staging/a"),
                List.of(assetOne, assetTwo), List.of(reviewMediaId));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2, 1);

        int result = new MediaPurgeStateService(jdbcTemplate).markPurged(userId, batch);

        assertThat(result).isEqualTo(3);
        verify(jdbcTemplate).update(
                eq("UPDATE media_assets SET status = 'PURGED', version = version + 1 "
                        + "WHERE provider_id = ? AND id IN (?,?) AND status = 'PURGE_PENDING'"),
                aryEq(new Object[]{userId, assetOne, assetTwo}));
        verify(jdbcTemplate).update(
                eq("UPDATE review_media SET status = 'PURGED', version = version + 1 "
                        + "WHERE uploader_id = ? AND id IN (?) AND status = 'PURGE_PENDING'"),
                aryEq(new Object[]{userId, reviewMediaId}));
    }

    @Test
    void emptyBatchDoesNotIssueBroadStatusUpdates() {
        UUID userId = UUID.randomUUID();
        var batch = new MediaPurgeStateService.PurgeBatch(List.of(), List.of(), List.of());

        int result = new MediaPurgeStateService(jdbcTemplate).markPurged(userId, batch);

        assertThat(result).isZero();
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }
}
