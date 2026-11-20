package com.marketplace.media.spi;

import com.marketplace.media.MediaPurgeStateService;
import com.marketplace.media.MediaService;
import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * G3: media implementation of the shared account-erasure port.
 *
 * <p>The state service commits PURGE_PENDING first. Only after every known
 * object (including every S3 version and delete marker where supported) has
 * been removed does this adapter move those rows to PURGED. Any storage
 * exception leaves the pending tombstone in place; the next orchestration run
 * retries the remaining keys. No object-store failure is converted to success.</p>
 */
@Component
public class MediaPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(MediaPurgeAdapter.class);

    private final MediaPurgeStateService purgeState;
    private final MediaService mediaService;

    public MediaPurgeAdapter(MediaPurgeStateService purgeState, MediaService mediaService) {
        this.purgeState = purgeState;
        this.mediaService = mediaService;
    }

    @Override
    public int purgeAuthoredTexts(UUID userId) {
        // The pending state commits in its own transaction before network I/O.
        // On failure, the state remains hidden and is selected again on retry.
        MediaPurgeStateService.PurgeBatch batch = purgeState.markPendingAndCollectKeys(userId);
        if (!batch.objectKeys().isEmpty()) {
            mediaService.destroyObjectsForErasure(batch.objectKeys());
        }

        // This separate transaction finalizes only rows captured by this
        // attempt, never rows added by an overlapping purge after the snapshot.
        int purgedRows = purgeState.markPurged(userId, batch);
        log.info("G3 media purge completed: userId={}, tombstonedRows={}, objectKeys={}",
                userId, purgedRows, batch.objectKeys().size());
        return purgedRows;
    }
}
