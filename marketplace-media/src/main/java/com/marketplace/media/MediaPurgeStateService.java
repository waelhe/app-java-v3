package com.marketplace.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * G3: persists the media-erasure state machine around the external object-store
 * operation. PURGE_PENDING blocks normal reads but remains retryable; PURGED is
 * written only after the storage adapter confirms that all known objects have
 * been removed.
 *
 * <p>The methods commit separately. If storage deletion fails, the first commit
 * preserves the access tombstone and a later erasure run retries the exact keys.</p>
 */
@Service
public class MediaPurgeStateService {
    private final JdbcTemplate jdbcTemplate;

    public MediaPurgeStateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Commits the retryable tombstone before any object-store network I/O. */
    @Transactional
    public List<String> markPendingAndCollectKeys(UUID userId) {
        // Serialize row creation with the snapshot-and-tombstone transaction.
        // Upload request paths take this same transaction-scoped lock.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('marketplace-media-account:' || CAST(? AS text), 0))",
                rs -> { }, userId);

        List<String> keys = new ArrayList<>();
        RowCallbackHandler assetKeys = rs -> {
            String objectKey = rs.getString(1);
            if (objectKey != null) keys.add(objectKey);
            String thumbKey = rs.getString(2);
            if (thumbKey != null) keys.add(thumbKey);
            String uploadKey = rs.getString(3);
            if (uploadKey != null) keys.add(uploadKey);
        };
        jdbcTemplate.query(
                "SELECT object_key, thumb_object_key, upload_object_key FROM media_assets "
                        + "WHERE provider_id = ? AND status <> 'PURGED' FOR UPDATE",
                assetKeys, userId);

        RowCallbackHandler reviewKeys = rs -> {
            String objectKey = rs.getString(1);
            if (objectKey != null) keys.add(objectKey);
            String uploadKey = rs.getString(2);
            if (uploadKey != null) keys.add(uploadKey);
        };
        jdbcTemplate.query(
                "SELECT object_key, upload_object_key FROM review_media "
                        + "WHERE uploader_id = ? AND status <> 'PURGED' FOR UPDATE",
                reviewKeys, userId);

        jdbcTemplate.update(
                "UPDATE media_assets SET status = 'PURGE_PENDING', version = version + 1 "
                        + "WHERE provider_id = ? AND status NOT IN ('PURGED', 'PURGE_PENDING')",
                userId);
        jdbcTemplate.update(
                "UPDATE review_media SET status = 'PURGE_PENDING', version = version + 1 "
                        + "WHERE uploader_id = ? AND status NOT IN ('PURGED', 'PURGE_PENDING')",
                userId);
        return List.copyOf(keys);
    }

    /** Finalizes only rows hidden by the committed pending tombstone. */
    @Transactional
    public int markPurged(UUID userId) {
        int assets = jdbcTemplate.update(
                "UPDATE media_assets SET status = 'PURGED', version = version + 1 "
                        + "WHERE provider_id = ? AND status = 'PURGE_PENDING'",
                userId);
        int reviewMedia = jdbcTemplate.update(
                "UPDATE review_media SET status = 'PURGED', version = version + 1 "
                        + "WHERE uploader_id = ? AND status = 'PURGE_PENDING'",
                userId);
        return assets + reviewMedia;
    }
}
