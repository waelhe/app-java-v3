package com.marketplace.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * JT-20 (the discovery waves — AC-20-05): the generalized follow row as
 * the /me management surface renders it — the member's own pair plus the
 * standing timestamp.
 *
 * <p>The view carries the RAW pair deliberately: {@code followableId}
 * sits in the type's own id space (the exact space the discovery rail's
 * {@code com.marketplace.shared.api.FollowedSourcesPort} union carries),
 * and the NAME composition is the reader's own decision — the rail
 * resolves its cards through its own lookups. The provider follow's
 * composed view (V93) is the precedent for composing where a promise
 * exists; this surface's registered promise is the pair itself (the
 * management shape: see what you follow, and withdraw it).
 */
public record FollowView(
        UUID id,
        String followableType,
        UUID followableId,
        Instant createdAt
) {

    static FollowView of(Follow follow) {
        return new FollowView(
                follow.getId(),
                follow.getFollowableType().name(),
                follow.getFollowableId(),
                follow.getCreatedAt());
    }
}
