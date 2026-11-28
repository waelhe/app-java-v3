package com.marketplace.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private static final int UPDATE_BATCH_SIZE = 1_000;

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

        Set<String> keys = new LinkedHashSet<>();
        List<UUID> mediaAssetIds = new ArrayList<>();
        RowCallbackHandler assetKeys = rs -> {
            mediaAssetIds.add((UUID) rs.getObject(1));
            String objectKey = rs.getString(2);
            if (objectKey != null) {
                keys.add(objectKey);
                // Thumbnail keys are deterministic (objectKey + "/thumb").
                // Include the candidate even when the pointer is null: S3
                // writes cannot roll back with the DB transaction, so a
                // successful PUT followed by a failed pointer commit leaves
                // an orphan that account erasure must still delete.
                keys.add(objectKey + "/thumb");
            }
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
        return new PurgeBatch(new ArrayList<>(keys), mediaAssetIds, reviewMediaIds);
    }

    /**
     * Finalizes only rows captured by this batch, and only while they remain
     * pending. A concurrent purge's newly captured rows stay retryable.
     *
     * <p>The Envers mirrors leave with the terminal state, in the same
     * transaction and with the same batch scoping (rulebook B5 "base tables
     * + mirrors" — the reviews adapter's {@code reviews_aud} scrub and b-4's
     * {@code users_aud} deletion are the family precedent): the captured
     * ids' revisions in {@code media_assets_aud} / {@code review_media_aud}
     * are deleted with the rows they mirror — a subject-broad delete would
     * instead touch an overlapping purge's not-yet-finalized rows, the exact
     * discipline this service's own tests pin. An empty batch issues no
     * statement at all. The mirrors are pure metadata (object keys, owner
     * id — no authored text) and this purge's updates run through
     * {@code JdbcTemplate}, so no new revisions supersede them.</p>
     */
    @Transactional
    public int markPurged(UUID userId, PurgeBatch batch) {
        int assets = markPurged("media_assets", "provider_id", userId, batch.mediaAssetIds());
        int reviewMedia = markPurged("review_media", "uploader_id", userId, batch.reviewMediaIds());
        deleteMirrors("media_assets_aud", "provider_id", userId, batch.mediaAssetIds());
        deleteMirrors("review_media_aud", "uploader_id", userId, batch.reviewMediaIds());
        return assets + reviewMedia;
    }

    /** Deletes the captured ids' Envers revisions, in the same 1,000-id batches. */
    private void deleteMirrors(String table, String ownerColumn, UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        for (int start = 0; start < ids.size(); start += UPDATE_BATCH_SIZE) {
            List<UUID> chunk = ids.subList(start, Math.min(start + UPDATE_BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = "DELETE FROM " + table + " WHERE " + ownerColumn + " = ? AND id IN (" + placeholders + ")";
            Object[] args = new Object[chunk.size() + 1];
            args[0] = userId;
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 1] = chunk.get(i);
            }
            jdbcTemplate.update(sql, args);
        }
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
        int updated = 0;
        for (int start = 0; start < ids.size(); start += UPDATE_BATCH_SIZE) {
            List<UUID> batch = ids.subList(start, Math.min(start + UPDATE_BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            String sql = "UPDATE " + table + " SET " + assignment + ", version = version + 1 "
                    + "WHERE " + ownerColumn + " = ? AND id IN (" + placeholders + ") AND " + predicate;
            Object[] args = new Object[batch.size() + 1];
            args[0] = userId;
            for (int i = 0; i < batch.size(); i++) {
                args[i + 1] = batch.get(i);
            }
            updated += jdbcTemplate.update(sql, args);
        }
        return updated;
    }
}
