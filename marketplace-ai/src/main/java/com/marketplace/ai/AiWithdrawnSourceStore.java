package com.marketplace.ai;

import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The AI module's exact withdrawal records — the relational companion to the
 * public vector index, and the authoritative "this identity is gone" fact.
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
 */
public final class AiWithdrawnSourceStore {

    private final JdbcTemplate jdbcTemplate;

    public AiWithdrawnSourceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
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
