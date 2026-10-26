package com.marketplace.knowledge.spi;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.KnowledgeSourceStatePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Row 26 (agent findings 2026-10-26 14:33 export): the knowledge module's
 * implementation of the {@link KnowledgeSourceStatePort} cross-module
 * contract — the AUTHORITATIVE publication state the AI module's guarded
 * retrieval asks for, one point read per source.
 *
 * <p>The live fact is the entry's OWN state, read from the table that
 * owns it: the row exists with {@code is_deleted = FALSE} (the author's
 * withdrawal soft-deletes it — the soft-delete predicate is EXPLICIT in
 * the native statement, mirroring the module's own purge statements'
 * discipline), and its authored text is not the content-purge tombstone
 * ({@link AuthoredContentPurgePort#PURGED_MARKER} on either column — the
 * erasure's own representation). Everything else — the durable AI
 * withdrawal record, the asynchronous vector deletion — stays as the
 * belt-and-braces it already is.</p>
 */
@Component
public class KnowledgeSourceStateAdapter implements KnowledgeSourceStatePort {

    /**
     * The one-statement live check. The soft-delete predicate is explicit
     * (native SQL bypasses Hibernate's filter — the module's own purge
     * statements' discipline), and the tombstone comparison covers BOTH
     * authored columns (either one alone means the erasure wrote it).
     */
    static final String LIVE_CHECK = """
            SELECT EXISTS(
                SELECT 1 FROM knowledge_entries
                WHERE id = ?
                  AND is_deleted = FALSE
                  AND title <> ?
                  AND body <> ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeSourceStateAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSourceContentLive(UUID entryId) {
        Boolean live = jdbcTemplate.queryForObject(LIVE_CHECK, Boolean.class,
                entryId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        return Boolean.TRUE.equals(live);
    }
}
