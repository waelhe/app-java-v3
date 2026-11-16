package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L41 (neighborhood community plan §5 — the membership anchor): one
 * neighborhood membership in the account's Art. 20 export (the b-2
 * self-service read the identity module aggregates through
 * {@link CommunityExportPort}).
 *
 * <p>The export is a faithful copy of the stored facts, not a re-typed
 * projection: the location travels as its {@code geo_locations} id (the
 * membership's own stored reference — the geo tree's display names are
 * the public geo surface's concern, not this record's), the verification
 * state as the stored enum name, and {@code memberSince} as the domain's
 * own timestamp. Soft-deleted (left) memberships are included — the b-5
 * discrimination the saved-search export established: surface deletion
 * is a visibility flag, not an erasure.
 */
public record CommunityMembershipExportEntry(
        UUID id,
        UUID locationId,
        String verificationState,
        Instant memberSince,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}
