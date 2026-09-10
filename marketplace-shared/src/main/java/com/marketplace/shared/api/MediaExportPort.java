package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the media module's share of a data-subject export.
 * Implemented by {@code MediaExportAdapter} (marketplace-media), consumed
 * by the identity module's export aggregation — the plan's R3 shape (each
 * module exports its share through its own port).
 *
 * <p>Ownership basis (the measured A1 fact): {@code media_assets.provider_id}
 * is a user id (V2/V32) — the export resolves the requester's assets by
 * that column directly.
 */
public interface MediaExportPort {

    /**
     * Every live media asset owned by {@code userId}, in creation order with
     * the id as the stable secondary key (a total order — tied timestamps
     * keep a deterministic document order).
     */
    List<MediaExportEntry> exportForOwner(UUID userId);
}
