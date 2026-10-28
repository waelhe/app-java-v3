package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 32 (agent findings 2026-10-25, CWE-367): the retrieval-side half of
 * the fix — the public retrieval boundary excludes durably withdrawn
 * sources, so no stale vector, however it came to exist, can disclose
 * withdrawn content.
 */
class AiWithdrawalGuardedVectorStoreTest {

    private static Document document(String id, String sourceId) {
        Map<String, Object> metadata = sourceId != null
                ? Map.of(AiKnowledgeGateway.SOURCE_ID_METADATA_KEY, sourceId)
                : Map.of();
        return new Document(id, "content of " + id, metadata);
    }

    @Test
    void similaritySearchDropsDocumentsOfDurablyWithdrawnSources() {
        VectorStore delegate = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        SearchRequest request = SearchRequest.builder().query("neighborhood faq").build();
        when(delegate.similaritySearch(request)).thenReturn(List.of(
                document("d1", "withdrawn-entry"),
                document("d2", "live-entry"),
                document("d3", null)));
        when(withdrawnSources.withdrawnAmong(Set.of("withdrawn-entry", "live-entry")))
                .thenReturn(Set.of("withdrawn-entry"));

        List<Document> guarded = new AiWithdrawalGuardedVectorStore(delegate, withdrawnSources)
                .similaritySearch(request);

        assertThat(guarded).extracting(Document::getId).containsExactly("d2", "d3");
    }

    @Test
    void similaritySearchPassesEverythingThroughWhenNothingIsWithdrawn() {
        VectorStore delegate = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        SearchRequest request = SearchRequest.builder().query("q").build();
        when(delegate.similaritySearch(request)).thenReturn(List.of(
                document("d1", "live-1"), document("d2", "live-2")));
        when(withdrawnSources.withdrawnAmong(anyCollection())).thenReturn(Set.of());

        List<Document> guarded = new AiWithdrawalGuardedVectorStore(delegate, withdrawnSources)
                .similaritySearch(request);

        assertThat(guarded).extracting(Document::getId).containsExactly("d1", "d2");
    }

    @Test
    void similaritySearchSkipsTheWithdrawalReadEntirelyWhenNoDocumentCarriesASourceId() {
        VectorStore delegate = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        SearchRequest request = SearchRequest.builder().query("q").build();
        when(delegate.similaritySearch(request)).thenReturn(List.of(
                document("d1", null), document("d2", null)));

        List<Document> guarded = new AiWithdrawalGuardedVectorStore(delegate, withdrawnSources)
                .similaritySearch(request);

        assertThat(guarded).extracting(Document::getId).containsExactly("d1", "d2");
        verify(withdrawnSources, never()).withdrawnAmong(anyCollection());
    }

    @Test
    void similaritySearchOfAnEmptyResultNeverTouchesTheWithdrawalStore() {
        VectorStore delegate = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        SearchRequest request = SearchRequest.builder().query("q").build();
        when(delegate.similaritySearch(request)).thenReturn(List.of());

        assertThat(new AiWithdrawalGuardedVectorStore(delegate, withdrawnSources).similaritySearch(request))
                .isEmpty();
        verify(withdrawnSources, never()).withdrawnAmong(anyCollection());
    }

    @Test
    void writesArePassedThroughUntouchedTheProtocolOwnsThem() {
        VectorStore delegate = mock(VectorStore.class);
        AiWithdrawnSourceStore withdrawnSources = mock(AiWithdrawnSourceStore.class);
        AiWithdrawalGuardedVectorStore guarded = new AiWithdrawalGuardedVectorStore(delegate, withdrawnSources);

        List<Document> documents = List.of(document("d1", "s"));
        guarded.add(documents);
        guarded.delete(List.of("d1"));
        guarded.delete(mock(Filter.Expression.class));

        verify(delegate).add(documents);
        verify(delegate).delete(List.of("d1"));
        verify(delegate).delete(any(Filter.Expression.class));
        // the guarded view never reads the index on the write path
        verify(withdrawnSources, never()).withdrawnAmong(anyCollection());
    }

    @Test
    void theWrappedStoreIdentityIsPreservedForObservabilityAndNativeAccess() {
        VectorStore delegate = mock(VectorStore.class);
        when(delegate.getName()).thenReturn("PgVectorStore");
        when(delegate.getNativeClient()).thenReturn(java.util.Optional.of("native"));

        AiWithdrawalGuardedVectorStore guarded =
                new AiWithdrawalGuardedVectorStore(delegate, mock(AiWithdrawnSourceStore.class));

        assertThat(guarded.getName()).isEqualTo("PgVectorStore");
        assertThat(guarded.getNativeClient()).contains("native");
    }
}
