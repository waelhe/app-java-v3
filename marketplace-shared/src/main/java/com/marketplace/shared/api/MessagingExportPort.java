package com.marketplace.shared.api;

import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the messaging module's share of a data-subject
 * export. Implemented by {@code MessagingExportAdapter}
 * (marketplace-messaging), consumed by the identity module's export
 * aggregation — the plan's R3 shape (each module exports its share through
 * its own port).
 */
public interface MessagingExportPort {

    /**
     * The conversations the user participates in plus the messages he sent
     * (both in creation order with the id as the stable secondary key — a
     * total order so tied timestamps keep a deterministic document order);
     * the counterparty's messages are never included.
     */
    MessagingExportData exportForParticipant(UUID userId);
}
