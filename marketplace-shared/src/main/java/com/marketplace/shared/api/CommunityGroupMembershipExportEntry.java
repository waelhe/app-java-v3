package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L51 (the Nextdoor-2026 completeness wave — gap #6, the neighbors
 * groups): one membership the subject took in a neighborhood group —
 * the belonging declaration as the member's own stored fact, in the
 * account's Art. 20 export.
 *
 * <p>The membership is identifiers-and-timestamps personal data — no
 * authored text rides it (the reaction row's and the seat's own
 * reasoning: the fact IS the data). The group travels as its opaque
 * id; left (soft-deleted) memberships are included — the b-5
 * discrimination verbatim: a left group is still the subject's stored
 * belonging history until the retention window closes.
 */
public record CommunityGroupMembershipExportEntry(
        UUID id,
        UUID groupId,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}
