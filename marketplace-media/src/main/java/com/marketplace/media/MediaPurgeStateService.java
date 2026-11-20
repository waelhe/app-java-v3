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
 * written only after the storage adapter confirms removal of the objects
 * captured by the same purge batch.
 *
 * <p>The methods commit separately. If storage deletion fails, the first commit
 * preserves the access tombstone and a later erasure run retries the exact keys.
 * Finalization is batch-scoped so one overlapping purge cannot mark another
 * purge's still-undeleted rows as PURGED.</p>
 */
@Service
public class MediaPurgeStateService {
    private final JdbcTemplate jdbcTemplate;

    public MediaPurgeStateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Immutable snapshot of one purge attempt. Row ids bind the later state
     * transition to exactly the rows whose object keys were collected here.
     */
    public record PurgeBatch(
            List<String> objectKeys,
            List<UUID> mediaAssetIds,
            List<UUID> reviewMediaIds) {
        public PurgeBatch {
            objectKeys = List.copyOf(objectKeys);
            mediaAssetIds = List.copyOf(mediaAssetIds);
            reviewMediaIds = List.copyOf(reviewMediaIds);
        }
    }

    /** Commits the retryable tombstone and captures the rows before storage I/O. */
    @Transactional
    public PurgeBatch markPendingAndCollectKeys(UUID userId) {
        // Serialize row creation with the snapshot-and-tombstone transaction.
        // Upload request paths take this same transaction-scoped lock.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('marketplace-media-account:' || CAST(? AS text), 0))",
                rs -> { }, userId);

        List<String> keys = new ArrayList<>();
        List<UUID> mediaAssetIds = new ArrayList<>();
        RowCallbackHandler assetKeys = rs -> {
            mediaAssetIds.add((UUID) rs.getObject(1));
            String objectKey = rs.getString(2);
            if (objectKey != null) keys.add(objectKey);
            String thumbKey = rs.getString(3);
            if (thumbKey != null) keys.add(thumbKey);
            String uploadKey = rs.getString(4);
            if (uploadKey != null) keys.add(uploadKey);
        };
        jdbcTemplate.query(
                "SELECT id, object_key, thumb_object_key, upload_object_key FROM media_assets "
                        + "WHERE provider_id = ? AND status <> 'PURGED' FOR UPDATE",
                assetKeys, userId);

        List<UUID> reviewMediaIds = new ArrayList<>();
        RowCallbackHandler reviewKeys = rs -> {
            reviewMediaIds.add((UUID) rs.getObject(1));
            String objectKey = rs.getString(2);
            if (objectKey != null) keys.add(objectKey);
            String uploadKey = rs.getString(3);
            if (uploadKey != null) keys.add(uploadKey);
        };
        jdbcTemplate.query(
                "SELECT id, object_key, upload_object_key FROM review_media "
                        + "WHERE uploader_id = ? AND status <> 'PURGED' FOR UPDATE",
                reviewKeys, userId);

        // Only rows captured above belong to this attempt. A row created by a
        // later overlapping upload/purge must not be silently absorbed into
        // this batch without its keys being deleted.
        markPending("media_assets", "provider_id", userId, mediaAssetIds);
        markPending("review_media", "uploader_id", userId, reviewMediaIds);
        return new PurgeBatch(keys, mediaAssetIds, reviewMediaIds);
    }

    /**
     * Finalizes only rows captured by this batch, and only while they remain
     * pending. A concurrent purge's newly captured rows stay retryable.
     */
    @Transactional
    public int markPurged(UUID userId, PurgeBatch batch) {
        int assets = markPurged("media_assets", "provider_id", userId, batch.mediaAssetIds());
        int reviewMedia = markPurged("review_media", "uploader_id", userId, batch.reviewMediaIds());
        return assets + reviewMedia;
    }

    private void markPending(String table, String ownerColumn, UUID userId, List<UUID> ids) {
        updateBatch(table, ownerColumn, userId, ids,
                "status NOT IN ('PURGED', 'PURGE_PENDING')",
                "status = 'PURGE_PENDING'");
    }

    private int markPurged(String table, String ownerColumn, UUID userId, List<UUID> ids) {
        return updateBatch(table, ownerColumn, userId, ids,
                "status = 'PURGE_PENDING'",
                "status = 'PURGED'");
    }

    private int updateBatch(
            String table, String ownerColumn, UUID userId, List<UUID> ids,
            String predicate, String assignment) {
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "UPDATE " + table + " SET " + assignment + ", version = version + 1 "
                + "WHERE " + ownerColumn + " = ? AND id IN (" + placeholders + ") AND " + predicate;
        Object[] args = new Object[ids.size() + 1];
        args[0] = userId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return jdbcTemplate.update(sql, args);
    }
}
