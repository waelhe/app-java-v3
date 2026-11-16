package com.marketplace.ai;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The AI module's exact withdrawal records — the relational companion to the
 * public vector index, and the authoritative "this identity is gone" fact.
 * Since row 32 of the agent findings (2026-10-25) this store is also the
 * relational boundary of the per-source publication/withdrawal protocol:
 * the cluster-wide transaction lock that serializes the two operations
 * across application instances, and the batched read the public retrieval
 * boundary uses to exclude withdrawn sources.
 *
 * <p><b>Why an exact keyed lookup, not a vector search:</b> Spring AI's
 * {@code VectorStore} interface offers {@code similaritySearch} as its only
 * read path, and pgvector applies metadata filters after the approximate
 * HNSW/IVFFlat index scan (the official pgvector repository's filtering
 * notes; the Spring AI pgvector reference carries the same caveat), so a
 * filtered {@code topK(1)} probe can return no row even though the record
 * exists. Approximate nearest-neighbor recall must therefore never serve as
 * an existence oracle for the withdrawal decision — a plain relational row
 * looked up by its primary key is exact by construction.</p>
 *
 * <p><b>Durability and idempotence:</b> the table lives in the same
 * Flyway-managed database as the vector store (V162), so the record
 * survives restarts and blocks event-publication-registry replays.
 * Re-recording an already-recorded source is a no-op
 * ({@code ON CONFLICT DO NOTHING}), making replayed withdrawal events
 * idempotent at the SQL boundary.</p>
 *
 * <p><b>The per-source cluster-wide lock (row 32, CWE-367):</b> JVM-local
 * stripes cannot serialize a publication on one instance against a
 * withdrawal on another — both Modulith listeners run asynchronously in
 * separate transactions, so without a shared protocol another instance can
 * complete a withdrawal between this instance's guard read and its vector
 * insertion, re-exposing withdrawn content. The transaction-scoped advisory
 * lock below is that shared protocol: keyed per source identity, held to the
 * surrounding transaction's commit, and therefore covering the database
 * record, the vector writes, and their shared completion atomically. It
 * follows the repository's established advisory families (the W1 r9
 * registry's {@code hashtextextended(:key, 12)}, the saved-search scan's
 * {@code 'saved-search-scan:'} prefix) — the {@code 'ai-knowledge-source:'}
 * prefix namespaces this family away from every other lock domain by
 * string, not by hash luck.</p>
 */
public final class AiWithdrawnSourceStore {

    /**
     * Row 32 (agent findings 2026-10-25 — CWE-367): the per-source advisory
     * transaction lock. The blocking form is deliberate — a publication that
     * races a withdrawal must WAIT for the withdrawal's commit and then read
     * the record, not skip ahead of it; the coalescing try-form is wrong
     * here (it would let a publication win a race against an in-flight
     * withdrawal and re-expose withdrawn content). Transaction-scoped, so it
     * is auto-released at commit or rollback — no unlock path to forget.
     */
    static final String SOURCE_TRANSACTION_LOCK =
            "SELECT pg_advisory_xact_lock(hashtextextended('ai-knowledge-source:' || ?::text, 0))";

    /**
     * The batched exact-key read the public retrieval boundary composes —
     * bounded IN-lists over the primary key, no recall semantics involved.
     */
    private static final int WITHDRAWN_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public AiWithdrawnSourceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Acquires the per-source cluster-wide advisory transaction lock — the
     * shared serialization point of the publication/withdrawal protocol
     * (row 32, CWE-367). MUST be called inside the transaction that performs
     * the guarded operation: the lock is held to that transaction's commit,
     * so the withdrawal-record read, the record write, the vector writes,
     * and their visibility all serialize as one per-source critical section
     * across every application instance sharing this database. A concurrent
     * same-source operation on any instance blocks here until the holder
     * commits, then observes the holder's committed state.
     *
     * @param sourceId the source identity to serialize on
     */
    public void acquireSourceTransactionLock(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        // The lock's effect is server-side; queryForList is the repository's
        // established parameterized form for advisory-lock statements (the
        // saved-search scan's try-variant reads its Boolean through the same
        // shape) and tolerates the function's void result column.
        jdbcTemplate.queryForList(SOURCE_TRANSACTION_LOCK, Object.class, sourceId);
    }

    /**
     * The exact-key batched read: which of the candidate source identities
     * are durably recorded as withdrawn. This is the retrieval-side
     * exclusion (row 32's belt-and-braces half) — a similarity search's
     * returned documents are filtered through this read so that no stale
     * vector, however it came to exist, can disclose withdrawn content on
     * the public retrieval boundary. B-tree point lookups in bounded
     * IN-lists; never approximate vector recall.
     *
     * @param sourceIds the source identities of retrieved documents
     * @return the subset that is durably withdrawn (never null)
     */
    public Set<String> withdrawnAmong(Collection<String> sourceIds) {
        Objects.requireNonNull(sourceIds, "sourceIds must not be null");
        List<String> distinct = sourceIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<String> withdrawn = new LinkedHashSet<>();
        for (int from = 0; from < distinct.size(); from += WITHDRAWN_BATCH_SIZE) {
            List<String> batch = distinct.subList(from, Math.min(from + WITHDRAWN_BATCH_SIZE, distinct.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            withdrawn.addAll(jdbcTemplate.queryForList(
                    "SELECT source_id FROM ai_withdrawn_sources WHERE source_id IN (" + placeholders + ")",
                    String.class,
                    batch.toArray()));
        }
        return Set.copyOf(withdrawn);
    }

    /**
     * Whether the exact withdrawal record exists for the source — a B-tree
     * point lookup on the primary key, with no recall semantics involved.
     *
     * @param sourceId the withdrawn source's identity
     * @return whether the source is durably recorded as withdrawn
     */
    public boolean exists(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_withdrawn_sources WHERE source_id = ?",
                Long.class, sourceId);
        return count != null && count > 0;
    }

    /**
     * Records the terminal withdrawal fact for the source. Idempotent: a
     * replayed (retried or registry-resubmitted) withdrawal event re-records
     * the same identity without error.
     *
     * @param sourceId the withdrawn source's identity
     */
    public void record(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        jdbcTemplate.update(
                "INSERT INTO ai_withdrawn_sources (source_id) VALUES (?) "
                        + "ON CONFLICT (source_id) DO NOTHING",
                sourceId);
    }
}
