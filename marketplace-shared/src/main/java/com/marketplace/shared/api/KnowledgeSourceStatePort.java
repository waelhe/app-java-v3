package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Row 26 (agent findings 2026-10-26 14:33 export — the AI withdrawal's
 * asynchronous gap): the port that lets the AI module read the knowledge
 * module's AUTHORITATIVE publication state for one source — the
 * {@code DisputeLookupPort} house pattern (the interface lives in
 * shared-api, the data owner implements, the consumer asks; no module
 * imports a sibling).
 *
 * <p>The gap this fact closes: the durable AI withdrawal record is
 * written by an AFTER_COMMIT listener — asynchronously, after the source
 * withdrawal's transaction commits — so a window exists in which the
 * source's indexed passages answer retrieval with NO withdrawal record:
 * the absence of a record was treated as permission. The recommendation's
 * second option verbatim: "check authoritative source state before
 * returning indexed passages. Keep asynchronous vector deletion as
 * cleanup." The live fact is the entry's OWN state — exists (not
 * soft-deleted by the author's withdrawal) and its authored text is not
 * the purge marker (the erasure's tombstone). The asynchronous record
 * and vector deletion stay exactly as they are: the belt-and-braces that
 * also blocks a late replayed publication from re-indexing withdrawn
 * content.</p>
 *
 * <p><b>Design decision — synchronous interface vs. event:</b> the
 * retrieval boundary needs the live fact <em>before</em> answering, so a
 * synchronous query is the official shape (the {@code DisputeLookupPort}
 * twin's own reasoning). The check is a bounded IN-list of point reads —
 * the retrieval set's size, never recall-wide.</p>
 */
public interface KnowledgeSourceStatePort {

    /**
     * Whether the knowledge entry's authored content is still LIVE: the
     * entry exists (the author's withdrawal soft-deletes it), and its
     * title and body are not the content-purge tombstone (the erasure's
     * own representation). A source that is not live must not answer
     * retrieval, however fresh or stale its indexed passages are.
     */
    boolean isSourceContentLive(UUID entryId);
}
