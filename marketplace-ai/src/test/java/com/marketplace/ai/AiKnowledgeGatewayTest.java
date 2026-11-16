package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiKnowledgeGatewayTest {

    /**
     * Row 32 (agent findings 2026-10-25, CWE-367): a REAL minimal
     * transaction manager that records begin/commit around the gateway's
     * operations — the per-source protocol is transaction-scoped, so the
     * tests must observe the transaction boundary, not just the calls
     * inside it.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    }

    @Test
    void replacesPublicSourceWithOfficialTokenChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        when(withdrawnSources.exists("faq-1")).thenReturn(false);

        new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(new ArrayList<>()))
                .replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                        "faq-1", "faq",
                        "Public marketplace FAQ. ".repeat(400)));

        verify(vectorStore).add(argThat(documents ->
                documents.size() > 1
                        && documents.stream().allMatch(document ->
                        "PUBLIC".equals(document.getMetadata().get("visibility"))
                                && "faq-1".equals(document.getMetadata().get("sourceId"))
                                && "faq".equals(document.getMetadata().get("sourceType")))));
        // The publication decision is the exact record — the path never
        // consults approximate nearest-neighbor recall at all.
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void rejectsBlankKnowledgeSource() {
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);

        assertThatIllegalArgumentException().isThrownBy(
                () -> new AiKnowledgeGateway.AiKnowledgeSource("", "faq", "content"));
    }

    /**
     * The CodeRabbit-adopted reversed-order contract (2026-10-09, the exact
     * hazard as stated): pgvector applies metadata filters after the
     * approximate HNSW/IVFFlat index scan, so a filtered topK(1) similarity
     * probe can return <em>no row even though the withdrawn record exists</em>.
     * The withdrawal decision is therefore the exact keyed record — with the
     * approximate recall simulated to miss, a delayed (retried or
     * registry-replayed) publication is still refused.
     */
    @Test
    void aDelayedPublicationAfterWithdrawalIsRefusedEvenWhenApproximateRecallMissesTheRecord() {
        VectorStore vectorStore = mock(VectorStore.class);
        // the pgvector hazard made explicit: the approximate probe would
        // find nothing — ANN recall must not be the existence oracle
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        // the exact record says withdrawn
        when(withdrawnSources.exists("faq-1")).thenReturn(true);

        AiKnowledgeGateway gateway =
                new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(new ArrayList<>()));
        gateway.markWithdrawn("faq-1");
        gateway.replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                "faq-1", "faq", "Late retried publication of withdrawn content"));

        // the late publication never reaches the index
        verify(vectorStore, never()).add(any());
    }

    @Test
    void withdrawalRecordsTheExactDurableFactAndDeletesTheVectors() {
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);

        new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(new ArrayList<>()))
                .markWithdrawn("faq-1");

        // the exact record is the durable withdrawal fact (idempotent at the
        // SQL boundary), and the source's vectors are removed
        verify(withdrawnSources).record("faq-1");
        verify(vectorStore).delete(any(Filter.Expression.class));
        // no tombstone document is written into the vector store anymore —
        // the relational record is the single source of the withdrawal fact
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void thePublicationGuardReadsTheExactRecordNeverApproximateRecall() {
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        when(withdrawnSources.exists("faq-1")).thenReturn(true);

        new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(new ArrayList<>()))
                .replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource("faq-1", "faq", "content"));

        // the guard consulted the exact record and refused — and the
        // decision path never touched the vector store at all
        verify(withdrawnSources).exists("faq-1");
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    /**
     * Row 32 (agent findings 2026-10-25, CWE-367): the publication protocol's
     * exact per-source order — the transaction opens FIRST, the cluster-wide
     * advisory lock is taken inside it BEFORE the withdrawal guard reads, and
     * the vector writes commit only after both. A check-then-insert outside
     * this order is exactly the cross-instance race the finding describes:
     * another instance's withdrawal could complete between the guard read and
     * the insertion.
     */
    @Test
    void publicationTakesThePerSourceLockInsideTheTransactionBeforeTheGuardReads() {
        List<String> events = new ArrayList<>();
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        when(withdrawnSources.exists("faq-1")).thenReturn(false);
        doAnswer(invocation -> {
            events.add("lock");
            return null;
        }).when(withdrawnSources).acquireSourceTransactionLock("faq-1");
        doAnswer(invocation -> {
            events.add("guard-read");
            return false;
        }).when(withdrawnSources).exists("faq-1");
        doAnswer(invocation -> {
            events.add("vector-delete");
            return null;
        }).when(vectorStore).delete(any(Filter.Expression.class));
        doAnswer(invocation -> {
            events.add("vector-add");
            return null;
        }).when(vectorStore).add(any());

        new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(events))
                .replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                        "faq-1", "faq", "content"));

        assertThat(events).containsExactly(
                "begin", "lock", "guard-read", "vector-delete", "vector-add", "commit");
    }

    /**
     * Row 32: the withdrawal protocol's order — transaction first, the
     * cluster-wide advisory lock inside it, then the durable record, then the
     * vector removal, then commit. The lock covering the record AND the
     * vector delete is what makes a racing publication on another instance
     * wait and then see the committed record.
     */
    @Test
    void withdrawalTakesThePerSourceLockInsideTheTransactionBeforeTheRecordAndTheVectorDelete() {
        List<String> events = new ArrayList<>();
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        doAnswer(invocation -> {
            events.add("lock");
            return null;
        }).when(withdrawnSources).acquireSourceTransactionLock("faq-1");
        doAnswer(invocation -> {
            events.add("record");
            return null;
        }).when(withdrawnSources).record("faq-1");
        doAnswer(invocation -> {
            events.add("vector-delete");
            return null;
        }).when(vectorStore).delete(any(Filter.Expression.class));

        new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(events))
                .markWithdrawn("faq-1");

        assertThat(events).containsExactly("begin", "lock", "record", "vector-delete", "commit");
    }

    /**
     * Row 32: the guard read happens under the lock, so the SKIP decision is
     * also made inside the transaction — a withdrawal that committed while
     * this publication waited on the lock is visible to the guard read, and
     * the publication never writes a vector.
     */
    @Test
    void theSkipDecisionIsAlsoMadeUnderTheLockInsideTheTransaction() {
        List<String> events = new ArrayList<>();
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        doAnswer(invocation -> {
            events.add("lock");
            return null;
        }).when(withdrawnSources).acquireSourceTransactionLock("faq-1");
        when(withdrawnSources.exists("faq-1")).thenReturn(true);

        new AiKnowledgeGateway(vectorStore, withdrawnSources, new RecordingTransactionManager(events))
                .replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource("faq-1", "faq", "content"));

        assertThat(events).containsExactly("begin", "lock", "commit");
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }
}
