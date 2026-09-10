package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the notifications module's share of a data-subject
 * export. Implemented by {@code NotificationExportAdapter}
 * (marketplace-notifications), consumed by the identity module's export
 * aggregation — the plan's R3 shape (each module exports its share through
 * its own port).
 */
public interface NotificationExportPort {

    /**
     * Every live notification addressed to {@code userId} (newest first,
     * the id as the stable secondary key — a total order so tied
     * timestamps keep a deterministic document order).
     */
    List<NotificationExportEntry> exportForRecipient(UUID userId);
}
