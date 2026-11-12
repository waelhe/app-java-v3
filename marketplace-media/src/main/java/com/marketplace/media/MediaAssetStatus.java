package com.marketplace.media;

import com.marketplace.shared.api.ConflictException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Asset state machine, following the {@code ListingStatus} house pattern.
 * A rejected/abandoned pending asset is simply soft-deleted by the owner —
 * there is no terminal REJECTED state to reach, so only one transition exists.
 *
 * <p><b>G3 (adversarial review 2026-11-10, owner-gate batch 6a — the
 * documented recommendation's two-degree media purge):</b> {@code PURGED}
 * is the erasure tombstone — a state no read rail presigns from (every
 * read rail filters {@code status = UPLOADED}, so a purged asset's
 * signed links stop being renewed the moment the tombstone lands —
 * Degree 1, «توقف الإتاحة»), and the state the account-erasure flow's
 * media adapter writes through its native SQL (never through this state
 * machine — the erasure flow is an administrative overwrite, the same
 * discipline every {@code AuthoredContentPurgePort} adapter carries for
 * its tombstones). It is terminal: no transition leaves it, and the
 * database's closed-set CHECK widens with it through the V300/V301 pair
 * (the V103/V104 house shape).</p>
 */
public enum MediaAssetStatus {
    PENDING_UPLOAD,
    UPLOADED,
    /** G3: the erasure tombstone — terminal, written only by the purge adapter's native SQL. */
    PURGED;

    public static final Map<MediaAssetStatus, Set<MediaAssetStatus>> TRANSITIONS =
            Collections.unmodifiableMap(Map.of(
                    PENDING_UPLOAD, EnumSet.of(UPLOADED),
                    UPLOADED, EnumSet.noneOf(MediaAssetStatus.class),
                    PURGED, EnumSet.noneOf(MediaAssetStatus.class)
            ));

    public void validateTransitionTo(MediaAssetStatus target) {
        Set<MediaAssetStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new ConflictException(
                    "Cannot transition media asset from " + this + " to " + target
            );
        }
    }
}
