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
 */
public enum MediaAssetStatus {
    PENDING_UPLOAD,
    UPLOADED;

    public static final Map<MediaAssetStatus, Set<MediaAssetStatus>> TRANSITIONS =
            Collections.unmodifiableMap(Map.of(
                    PENDING_UPLOAD, EnumSet.of(UPLOADED),
                    UPLOADED, EnumSet.noneOf(MediaAssetStatus.class)
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
