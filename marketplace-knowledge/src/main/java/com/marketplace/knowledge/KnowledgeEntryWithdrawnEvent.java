package com.marketplace.knowledge;

import java.util.UUID;

/**
 * B-14 (compliance plan C.4 — «search بالأحداث»): the entry's withdrawal
 * fact, published when the author withdraws the entry (the soft delete —
 * the row keeps its audit trail, the reads stop returning it). The
 * eventual search-side consumer drops the entry from its index on this
 * signal (the published/withdrawn pair is the COMPLETE integration
 * contract — an index that only ever upserts would serve withdrawn
 * entries forever).
 *
 * <p>Same shape and same ownership as
 * {@link KnowledgeEntryPublishedEvent} (the module-owned record on the
 * exposed {@code knowledge} interface, the complete fact, the
 * late-lander consumer).</p>
 *
 * @param entryId    the withdrawn entry's id
 * @param locationId the neighborhood the entry documented (the consumer's scoping axis)
 */
public record KnowledgeEntryWithdrawnEvent(
        UUID entryId,
        UUID locationId
) {
}
