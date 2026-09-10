package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the reviews module's share of a data-subject export.
 * Implemented by {@code ReviewExportAdapter} (marketplace-reviews),
 * consumed by the identity module's export aggregation — the plan's R3
 * shape (each module exports its share through its own port).
 */
public interface ReviewExportPort {

    /**
     * Every live review authored by {@code userId} (both directions — his
     * sent reviews), in creation order with the id as the stable secondary
     * key (a total order — tied timestamps keep a deterministic document
     * order).
     */
    List<ReviewExportEntry> exportForAuthor(UUID userId);
}
