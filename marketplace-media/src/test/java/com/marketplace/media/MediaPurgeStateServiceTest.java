package com.marketplace.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G3 state contract: access is blocked in a committed, retryable state before
 * storage I/O; only a later successful storage operation can finalize PURGED.
 */
@ExtendWith(MockitoExtension.class)
class MediaPurgeStateServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void markPendingCollectsOriginalsThumbnailsAndReviewMediaBeforeSettingRetryableState() throws Exception {
        UUID userId = UUID.randomUUID();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowCallbackHandler handler = invocation.getArgument(1);
            if (sql.contains("pg_advisory_xact_lock")) return null;
            ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
            if (sql.contains("media_assets")) {
                when(row.getString(1)).thenReturn("listings/1/a.jpg", "posts/2/b.jpg");
                when(row.getString(2)).thenReturn("listings/1/a.jpg/thumb").thenReturn((String) null);
                when(row.getString(3)).thenReturn("media-upload-staging/asset-a").thenReturn(null);
                handler.processRow(row);
                handler.processRow(row);
            } else {
                when(row.getString(1)).thenReturn("review-media/3/c.jpg");
                when(row.getString(2)).thenReturn("media-upload-staging/review-c");
                handler.processRow(row);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq(userId));
        when(jdbcTemplate.update(anyString(), eq(userId))).thenReturn(2, 1);

        var keys = new MediaPurgeStateService(jdbcTemplate).markPendingAndCollectKeys(userId);

        assertThat(keys).containsExactly(
                "listings/1/a.jpg", "listings/1/a.jpg/thumb", "media-upload-staging/asset-a",
                "posts/2/b.jpg", "review-media/3/c.jpg", "media-upload-staging/review-c");
        verify(jdbcTemplate).update(
                "UPDATE media_assets SET status = 'PURGE_PENDING', version = version + 1 "
                        + "WHERE provider_id = ? AND status NOT IN ('PURGED', 'PURGE_PENDING')",
                userId);
        verify(jdbcTemplate).update(
                "UPDATE review_media SET status = 'PURGE_PENDING', version = version + 1 "
                        + "WHERE uploader_id = ? AND status NOT IN ('PURGED', 'PURGE_PENDING')",
                userId);
    }

    @Test
    void markPendingIncludesPreviouslyPendingRowsSoFailedDeletionCanBeRetried() throws Exception {
        UUID userId = UUID.randomUUID();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("pg_advisory_xact_lock")) return null;
            assertThat(sql).contains("status <> 'PURGED'");
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
            if (sql.contains("media_assets")) {
                when(row.getString(1)).thenReturn("listings/retry.jpg");
                when(row.getString(2)).thenReturn(null);
                when(row.getString(3)).thenReturn("media-upload-staging/retry-asset");
            } else {
                when(row.getString(1)).thenReturn("review-media/retry.jpg");
                when(row.getString(2)).thenReturn("media-upload-staging/retry-review");
            }
            handler.processRow(row);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq(userId));
        when(jdbcTemplate.update(anyString(), eq(userId))).thenReturn(0, 0);

        var keys = new MediaPurgeStateService(jdbcTemplate).markPendingAndCollectKeys(userId);

        assertThat(keys).containsExactly("listings/retry.jpg", "media-upload-staging/retry-asset",
                "review-media/retry.jpg", "media-upload-staging/retry-review");
    }

    @Test
    void markPurgedOnlyFinalizesPendingRowsAndAdvancesTheirVersions() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(userId))).thenReturn(4, 2);

        int result = new MediaPurgeStateService(jdbcTemplate).markPurged(userId);

        assertThat(result).isEqualTo(6);
        verify(jdbcTemplate).update(
                "UPDATE media_assets SET status = 'PURGED', version = version + 1 "
                        + "WHERE provider_id = ? AND status = 'PURGE_PENDING'",
                userId);
        verify(jdbcTemplate).update(
                "UPDATE review_media SET status = 'PURGED', version = version + 1 "
                        + "WHERE uploader_id = ? AND status = 'PURGE_PENDING'",
                userId);
    }
}
