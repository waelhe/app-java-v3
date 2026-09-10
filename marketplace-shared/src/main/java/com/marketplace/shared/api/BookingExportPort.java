package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the booking module's share of a data-subject export.
 * Implemented by {@code BookingExportAdapter} (marketplace-booking),
 * consumed by the identity module's export aggregation — the plan's R3
 * shape: each module exports its share through its own port, aggregation
 * happens in identity, and no module boundary is crossed.
 *
 * <p>Scope (the plan's provenance rule): the bookings where the requester
 * is a first party — consumer or provider. Soft-deleted rows are excluded
 * by the house {@code @SoftDelete} filter on every query.
 */
public interface BookingExportPort {

    /**
     * Every live booking where {@code userId} is the consumer or the
     * provider, in creation order with the id as the stable secondary key
     * (a total order — tied timestamps keep a deterministic document
     * order).
     */
    List<BookingExportEntry> exportForParticipant(UUID userId);
}
