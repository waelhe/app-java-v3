package com.marketplace.media;

import com.marketplace.shared.api.ConflictException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Asset state machine, following the {@code ListingStatus} house pattern.
 * A rejected/abandoned pending asset is simply soft-deleted by the owner —
 * and an ABANDONED one (past the presign horizon plus the grace window,
 * never confirmed by its owner) is expired by the system's cleanup job,
 * never by a caller.
 */
public enum MediaAssetStatus {
    PENDING_UPLOAD,
    UPLOADED,

    /**
     * The 2026-11-06 #809 round (Major · Functional Correctness): the
     * system-reached terminal state for an abandoned pending upload —
     * older than the presign TTL plus the grace window when the cleanup
     * job ran. Terminal exactly like UPLOADED: no transition leaves it
     * (the object was best-effort deleted; the row stays as the audit
     * trail), and it never occupies a cap slot again (the live counts
     * exclude it).
     */
    EXPIRED;

    public static final Map<MediaAssetStatus, Set<MediaAssetStatus>> TRANSITIONS =
            Collections.unmodifiableMap(Map.of(
                    PENDING_UPLOAD, EnumSet.of(UPLOADED, EXPIRED),
                    UPLOADED, EnumSet.noneOf(MediaAssetStatus.class),
                    EXPIRED, EnumSet.noneOf(MediaAssetStatus.class)
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
