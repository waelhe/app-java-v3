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
 * documented two-degree media purge):</b> {@code PURGE_PENDING} is the
 * access tombstone — every read rail filters for {@code status = UPLOADED},
 * so a pending purge stops new signed-link issuance immediately while
 * remaining retryable if object-store deletion fails. {@code PURGED} is
 * the terminal post-destruction state, written by the erasure state service
 * only after the storage adapter confirms that every known object version
 * and delete marker has been removed. Both states are written through the
 * administrative erasure path, never through this public asset state
 * machine. The database CHECK widens through the V303/V304 pair.</p> */
public enum MediaAssetStatus {
    PENDING_UPLOAD,
    UPLOADED,
    /** G3: hidden from reads while storage deletion is pending and safely retryable. */
    PURGE_PENDING,
    /** G3: terminal state, written only after all known storage versions are deleted. */
    PURGED;

    public static final Map<MediaAssetStatus, Set<MediaAssetStatus>> TRANSITIONS =
            Collections.unmodifiableMap(Map.of(
                    PENDING_UPLOAD, EnumSet.of(UPLOADED),
                    UPLOADED, EnumSet.noneOf(MediaAssetStatus.class),
                    PURGE_PENDING, EnumSet.noneOf(MediaAssetStatus.class),
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
