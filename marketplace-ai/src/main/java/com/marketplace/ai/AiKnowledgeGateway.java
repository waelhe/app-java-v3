package com.marketplace.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

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
 * record exists. Every public source operation runs under a striped
 * per-source lock, so a concurrent publish/withdraw pair for one entry
 * serializes in submission order within this JVM. Public documents carry
 * the {@code sourceId} metadata key; retrieval paths filter
 * {@code visibility == 'PUBLIC'}.</p>
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

    private final VectorStore vectorStore;
    private final AiWithdrawnSourceStore withdrawnSources;
    private final TokenTextSplitter splitter;
    private final Lock[] sourceLocks;

    public AiKnowledgeGateway(VectorStore vectorStore, AiWithdrawnSourceStore withdrawnSources) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.withdrawnSources = Objects.requireNonNull(withdrawnSources, "withdrawnSources must not be null");
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
        runSerialized(source.sourceId(), () -> {
            if (isWithdrawn(source.sourceId())) {
                return;
            }
            Document sourceDocument = new Document(
                    source.content(),
                    Map.of(
                            "visibility", "PUBLIC",
                            "sourceId", source.sourceId(),
                            "sourceType", source.sourceType()
                    ));
            List<Document> replacement = splitter.apply(List.of(sourceDocument));
            vectorStore.delete(sourceFilter(source.sourceId()).build());
            vectorStore.add(replacement);
        });
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
        runSerialized(sourceId, () -> {
            withdrawnSources.record(sourceId);
            vectorStore.delete(sourceFilter(sourceId).build());
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
