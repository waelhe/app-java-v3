package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

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
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        new AiKnowledgeGateway(vectorStore).replacePublicSource(
                new AiKnowledgeGateway.AiKnowledgeSource(
                        "faq-1", "faq",
                        "Public marketplace FAQ. ".repeat(400)));

        verify(vectorStore).add(argThat(documents ->
                documents.size() > 1
                        && documents.stream().allMatch(document ->
                        "PUBLIC".equals(document.getMetadata().get("visibility"))
                                && "faq-1".equals(document.getMetadata().get("sourceId"))
                                && "faq".equals(document.getMetadata().get("sourceType")))));
    }

    @Test
    void rejectsBlankKnowledgeSource() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AiKnowledgeGateway.AiKnowledgeSource("", "faq", "content"));
    }

    /**
     * The CodeRabbit-adopted reversed-order contract: when the withdrawal
     * executed first, a delayed (retried or registry-replayed) publication
     * for the same source must NOT re-expose it — the durable tombstone
     * blocks the late write.
     */
    @Test
    void aDelayedPublicationAfterWithdrawalIsRefusedByTheTombstone() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document tombstone = new Document(
                "Withdrawn source faq-1",
                Map.of("visibility", "WITHDRAWN", "sourceId", "faq-1"));
        // the withdrawal ran first: the tombstone is the source's only document
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(tombstone));

        AiKnowledgeGateway gateway = new AiKnowledgeGateway(vectorStore);
        gateway.markWithdrawn("faq-1");
        gateway.replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                "faq-1", "faq", "Late retried publication of withdrawn content"));

        // the late publication never reaches the index
        verify(vectorStore, never()).add(argThat(documents ->
                documents.stream().anyMatch(document ->
                        "PUBLIC".equals(document.getMetadata().get("visibility")))));
    }

    @Test
    void withdrawalWritesTheDurableTombstoneThroughTheOfficialFilterDelete() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        new AiKnowledgeGateway(vectorStore).markWithdrawn("faq-1");

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(vectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "WITHDRAWN".equals(documents.get(0).getMetadata().get("visibility"))
                        && "faq-1".equals(documents.get(0).getMetadata().get("sourceId"))));
    }

    @Test
    void theTombstoneProbeFiltersByWithdrawnVisibilityAndSourceId() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        new AiKnowledgeGateway(vectorStore).replacePublicSource(
                new AiKnowledgeGateway.AiKnowledgeSource("faq-1", "faq", "content"));

        verify(vectorStore).similaritySearch(argThat((SearchRequest request) ->
                request.getFilterExpression() != null
                        && request.getFilterExpression().toString().contains("WITHDRAWN")
                        && request.getFilterExpression().toString().contains("faq-1")));
    }
}
