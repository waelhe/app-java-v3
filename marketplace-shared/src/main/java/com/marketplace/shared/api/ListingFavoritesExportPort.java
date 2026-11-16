package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * W3 (yelp-level plan §5 — G19, the review round's export leg): the
 * catalog module's contribution to the account export for the member's
 * saved listings — the standing {@code *ExportPort} house pattern (the
 * identity module's aggregation sees only this shared-api type, exactly
 * like {@code CommunityExportPort} and the booking/messaging ports
 * before it).
 *
 * <p>The export includes the subject's withdrawn favorites too: a
 * save-then-unsave pair is still the subject's stored relation history
 * until the retention window closes (b-5's discrimination — deletion at
 * the surface is a visibility flag, not an erasure).
 */
public interface ListingFavoritesExportPort {

    /**
     * Every listing the subject ever saved — live and withdrawn — in
     * stable {@code (created_at, id)} order. The favorite is the
     * member's own declared relation (which listing they kept, and
     * when), which is why it rides the b-2 export at all.
     */
    List<ListingFavoriteExportEntry> exportForOwner(UUID userId);
}
