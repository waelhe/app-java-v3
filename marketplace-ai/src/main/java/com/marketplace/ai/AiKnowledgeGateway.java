package com.marketplace.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The AI knowledge index over the public vector store, with the publish /
 * withdraw ordering hazards of Spring Modulith's asynchronous
 * {@code @ApplicationModuleListener} delivery closed at the root (the
 * CodeRabbit findings adopted with carrying tests).
 *
 * <p><b>The hazard:</b> a withdrawal that executes before a delayed
 * publication for the same entry (the async listener's retry/replay path —
 * a failed indexing attempt is resubmitted by the event publication
 * registry) would leave the index empty, and the late publication would
 * then re-expose an entry the author withdrew.</p>
 *
 * <p><b>The invariant that makes the guard complete:</b> withdrawal is
 * terminal for a knowledge entry's identity. The soft-deleted row never
 * surfaces again (Hibernate {@code @SoftDelete} filters every read), every
 * new contribution is a new identity, and the module's service offers no
 * un-withdraw — so after a withdrawal, <em>no</em> legitimate publication
 * for the same {@code sourceId} can ever exist. A durable withdrawal record
 * therefore never needs to be overridden, and no version/revision
 * stamping of the events is required.</p>
 *
 * <p><b>The mechanism:</b> {@link #markWithdrawn(String)} records the
 * terminal withdrawal fact in {@link AiWithdrawnSourceStore} — an exact
 * keyed relational record (V162) in the same Flyway-managed database, so
 * it survives restarts and blocks registry replays — and removes the
 * source's documents from the index. The record is written before the
 * vectors are deleted, so a crash between the two steps can only leave a
 * withdrawn source briefly stale in the index (cleared by the listener's
 * replay), never a withdrawn source re-exposable by a late publication.
 * {@link #replacePublicSource} refuses to index a source whose withdrawal
 * record exists. Public documents carry the {@code sourceId} metadata key;
 * retrieval paths filter {@code visibility == 'PUBLIC'}.</p>
 *
 * <p><b>The cross-instance protocol (row 32, agent findings 2026-10-25,
 * CWE-367):</b> the per-source stripes above serialize operations only
 * within one JVM — a publication on instance A and a withdrawal on
 * instance B (Spring Modulith's asynchronous listeners each run in their
 * own transaction) could interleave as check-then-insert against
 * record-then-delete, re-exposing withdrawn content on A after B's
 * withdrawal completed. Both operations now run inside ONE transaction per
 * source that first takes the cluster-wide, transaction-scoped advisory
 * lock {@code pg_advisory_xact_lock(hashtextextended('ai-knowledge-source:' || sourceId, 0))}
 * (the store's {@link AiWithdrawnSourceStore#acquireSourceTransactionLock}):
 * a racing same-source operation on ANY instance blocks until the holder's
 * transaction commits, then reads the holder's committed state. If the
 * withdrawal committed first, the publication's guard sees the record and
 * skips; if the publication committed first, the withdrawal's delete
 * removes the fresh vectors. The lock is held to commit, so it covers the
 * withdrawal record, the vector writes, and their shared completion —
 * exactly the shared per-source protocol the finding requires. The
 * transaction template uses the default {@code REQUIRED} propagation: it
 * joins the Modulith listener's surrounding transaction when present (the
 * record, the vectors, and the event-publication completion then commit
 * atomically) and starts its own otherwise. The retrieval boundary
 * excludes withdrawn sources independently of this protocol (see
 * {@link AiWithdrawalGuardedVectorStore}) — stale vectors can never
 * disclose withdrawn content even if they predate the protocol.</p>
 *
 * <p><b>Why the withdrawal decision is not a vector search:</b> Spring
 * AI's {@code VectorStore} interface offers {@code similaritySearch} as
 * its only read path, and pgvector applies metadata filters after the
 * approximate HNSW/IVFFlat index scan, so a filtered {@code topK(1)}
 * probe can return no row even though the withdrawn record exists —
 * approximate nearest-neighbor recall must never serve as an existence
 * oracle (the CodeRabbit-adopted root fix, 2026-10-09).</p>
 */
public final class AiKnowledgeGateway {

    private static final int SOURCE_LOCK_STRIPES = 64;

    /**
     * The metadata key every public knowledge document carries — the
     * retrieval boundary's withdrawal guard reads the same key, so one
     * definition serves both sides (the guarded view must never drift from
     * the writer's key).
     */
    public static final String SOURCE_ID_METADATA_KEY = "sourceId";

    private final VectorStore vectorStore;
    private final AiWithdrawnSourceStore withdrawnSources;
    private final TransactionTemplate sourceTransactions;
    private final TokenTextSplitter splitter;
    private final Lock[] sourceLocks;

    /**
     * @param vectorStore the public vector index (writes only — this
     *        gateway never reads it)
     * @param withdrawnSources the relational boundary of the withdrawal
     *        records and the per-source cluster lock
     * @param transactionManager the application's transaction manager —
     *        the per-source protocol REQUIRES a real transaction: the
     *        advisory lock is transaction-scoped and must be held across
     *        the record read/write, the vector writes, and their commit
     */
    public AiKnowledgeGateway(VectorStore vectorStore,
                              AiWithdrawnSourceStore withdrawnSources,
                              PlatformTransactionManager transactionManager) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.withdrawnSources = Objects.requireNonNull(withdrawnSources, "withdrawnSources must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // REQUIRED (the default): join the Modulith listener's surrounding
        // transaction when present, begin one otherwise — either way the
        // advisory lock is held to the commit that makes the record and
        // the vector writes visible together.
        this.sourceTransactions = template;
        this.splitter = TokenTextSplitter.builder().build();
        this.sourceLocks = new Lock[SOURCE_LOCK_STRIPES];
        for (int i = 0; i < SOURCE_LOCK_STRIPES; i++) {
            this.sourceLocks[i] = new ReentrantLock();
        }
    }

    /**
     * Indexes (or re-indexes) one public source as token-split documents.
     * A delayed publication for a source that was already withdrawn is
     * skipped — the withdrawal is terminal for the source's identity.
     */
    public void replacePublicSource(AiKnowledgeSource source) {
        Objects.requireNonNull(source, "source must not be null");
        runSerialized(source.sourceId(), () -> inSourceTransaction(source.sourceId(), () -> {
            if (isWithdrawn(source.sourceId())) {
                return;
            }
            Document sourceDocument = new Document(
                    source.content(),
                    Map.of(
                            "visibility", "PUBLIC",
                            SOURCE_ID_METADATA_KEY, source.sourceId(),
                            "sourceType", source.sourceType()
                    ));
            List<Document> replacement = splitter.apply(List.of(sourceDocument));
            vectorStore.delete(sourceFilter(source.sourceId()).build());
            vectorStore.add(replacement);
        }));
    }

    /**
     * Withdraws a source: records the durable, exact-keyed withdrawal fact
     * that blocks any late (retried or replayed) publication of the same
     * identity from re-exposing it, then removes every document the source
     * owns from the index. The record is idempotent under replays
     * ({@code ON CONFLICT DO NOTHING} at the SQL boundary).
     */
    public void markWithdrawn(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        runSerialized(sourceId, () -> inSourceTransaction(sourceId, () -> {
            withdrawnSources.record(sourceId);
            vectorStore.delete(sourceFilter(sourceId).build());
        }));
    }

    /**
     * The per-source critical section: one transaction that takes the
     * cluster-wide advisory lock FIRST, then runs the guarded operation.
     * The lock is auto-released at the transaction's commit or rollback,
     * so the operation's reads, writes, and their visibility serialize
     * against every other instance's same-source operation.
     */
    private void inSourceTransaction(String sourceId, Runnable operation) {
        sourceTransactions.executeWithoutResult(status -> {
            withdrawnSources.acquireSourceTransactionLock(sourceId);
            operation.run();
        });
    }

    /**
     * Whether the durable withdrawal record exists for the source — the
     * authoritative "this identity is gone" fact, read by exact primary-key
     * lookup (never by approximate vector recall; see the class javadoc).
     */
    private boolean isWithdrawn(String sourceId) {
        return withdrawnSources.exists(sourceId);
    }

    private FilterExpressionBuilder.Op sourceFilter(String sourceId) {
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        return filters.eq("sourceId", sourceId);
    }

    private void runSerialized(String sourceId, Runnable operation) {
        Lock lock = sourceLocks[Math.floorMod(sourceId.hashCode(), SOURCE_LOCK_STRIPES)];
        lock.lock();
        try {
            operation.run();
        }
        finally {
            lock.unlock();
        }
    }

    public record AiKnowledgeSource(String sourceId, String sourceType, String content) {
        public AiKnowledgeSource {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("sourceId must not be blank");
            }
            if (sourceType == null || sourceType.isBlank()) {
                throw new IllegalArgumentException("sourceType must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content must not be blank");
            }
        }
    }
}
