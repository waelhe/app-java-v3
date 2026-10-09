package com.marketplace.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
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
 * CodeRabbit finding adopted with a carrying test).
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
 * for the same {@code sourceId} can ever exist. A durable tombstone
 * therefore never needs to be overridden, and no version/revision
 * stamping of the events is required.</p>
 *
 * <p><b>The mechanism:</b> {@link #markWithdrawn(String)} replaces the
 * source's documents with a tombstone document carrying
 * {@code visibility=WITHDRAWN} — durable in the vector store itself, so it
 * survives restarts and blocks registry replays. {@link #replacePublicSource}
 * refuses to index a source whose tombstone exists. Every public source
 * operation runs under a striped per-source lock, so a concurrent
 * publish/withdraw pair for one entry serializes in submission order
 * within this JVM. Both public documents and tombstones are written with
 * the same {@code sourceId} metadata key; retrieval paths filter
 * {@code visibility == 'PUBLIC'}, so tombstones are never served.</p>
 */
public final class AiKnowledgeGateway {

    private static final int SOURCE_LOCK_STRIPES = 64;

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;
    private final Lock[] sourceLocks;

    public AiKnowledgeGateway(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
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
     * Withdraws a source: removes every document it owns and writes the
     * durable tombstone that blocks any late (retried or replayed)
     * publication of the same identity from re-exposing it.
     */
    public void markWithdrawn(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        runSerialized(sourceId, () -> {
            vectorStore.delete(sourceFilter(sourceId).build());
            vectorStore.add(List.of(new Document(
                    "Withdrawn source " + sourceId,
                    Map.of(
                            "visibility", "WITHDRAWN",
                            "sourceId", sourceId
                    ))));
        });
    }

    /**
     * Whether the durable withdrawal tombstone exists for the source —
     * the authoritative "this identity is gone" fact inside the store.
     */
    private boolean isWithdrawn(String sourceId) {
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        return !vectorStore.similaritySearch(SearchRequest.builder()
                        .query(sourceId)
                        .topK(1)
                        .filterExpression(filters.and(
                                filters.eq("visibility", "WITHDRAWN"),
                                filters.eq("sourceId", sourceId)).build())
                        .build())
                .isEmpty();
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
