package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiKnowledgeGatewayTest {

    @Test
    void replacesPublicSourceWithOfficialTokenChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        when(withdrawnSources.exists("faq-1")).thenReturn(false);

        new AiKnowledgeGateway(vectorStore, withdrawnSources).replacePublicSource(
                new AiKnowledgeGateway.AiKnowledgeSource(
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

        AiKnowledgeGateway gateway = new AiKnowledgeGateway(vectorStore, withdrawnSources);
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

        new AiKnowledgeGateway(vectorStore, withdrawnSources).markWithdrawn("faq-1");

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

        new AiKnowledgeGateway(vectorStore, withdrawnSources).replacePublicSource(
                new AiKnowledgeGateway.AiKnowledgeSource("faq-1", "faq", "content"));

        // the guard consulted the exact record and refused — and the
        // decision path never touched the vector store at all
        verify(withdrawnSources).exists("faq-1");
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }
}
