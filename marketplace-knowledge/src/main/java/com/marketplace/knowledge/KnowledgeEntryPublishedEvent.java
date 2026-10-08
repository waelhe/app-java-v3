package com.marketplace.knowledge;

import java.util.UUID;

/**
 * B-14 (compliance plan C.4 — «search بالأحداث»): the entry's
 * live-indexing fact, published on every contribution and every revision
 * (the upsert signal). A module-owned record on the exposed
 * {@code knowledge} NamedInterface (the B-06 {@code DisputeOpenedEvent}
 * pattern — the shared-api placement needs Track A's CR, the
 * module-owned placement compiles for any consumer that declares
 * {@code knowledge :: knowledge}).
 *
 * <p><b>The fact is COMPLETE by design</b> (the
 * {@code MessageReceivedEvent} discipline): the consumer never
 * re-derives anything — no lookup port, no second read. The eventual
 * consumer is the search side (a RESERVE module today — the ownership
 * matrix's untouched column): the late-lander rule assigns the listener
 * to whoever lands it, and the event catalog registration note rides
 * the worklog. Publication is verified by the module's own tests (the
 * mock-verify discipline) and the app-level IT's {@code PublishedEvents}
 * in CI.</p>
 *
 * @param entryId    the entry's id
 * @param locationId the neighborhood the entry documents (the geo level-3 node)
 * @param category   the guide's own vocabulary value
 * @param title      the entry's title
 * @param body       the entry's body (the complete indexing text)
 * @param authorId   the contributor's user id (the trust/identity axis)
 */
public record KnowledgeEntryPublishedEvent(
        UUID entryId,
        UUID locationId,
        KnowledgeCategory category,
        String title,
        String body,
        UUID authorId
) {
}
