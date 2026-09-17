package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * L41 (neighborhood community plan §5): the community module's
 * contribution to the account export (the standing {@code *ExportPort}
 * house pattern, R3 — the identity module's aggregation sees only this
 * shared-api type, exactly like {@code SavedSearchExportPort} before
 * it). The export includes the subject's soft-deleted memberships too:
 * a left neighborhood is still the subject's stored personal data (their
 * place of residence declaration) until the retention window closes
 * (b-5's discrimination — deletion at the surface is a visibility flag,
 * not an erasure).
 */
public interface CommunityExportPort {

    /**
     * Every membership the subject ever declared — active and left — in
     * stable {@code (created_at, id)} order. The membership is personal
     * data (the user's self-declared location), which is why it rides
     * the b-2 export at all.
     */
    List<CommunityMembershipExportEntry> exportForOwner(UUID userId);
}
