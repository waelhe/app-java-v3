package com.marketplace.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AiKnowledgeGateway {

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public AiKnowledgeGateway(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.splitter = TokenTextSplitter.builder().build();
    }

    public void replacePublicSource(AiKnowledgeSource source) {
        Objects.requireNonNull(source, "source must not be null");

        // Prepare the replacement documents BEFORE deleting the current
        // ones (the CodeRabbit-measured loss window): a splitter failure
        // now leaves the existing source untouched instead of deleted-
        // with-no-replacement. The official VectorStore contract exposes
        // delete and add as two separate operations with no atomic
        // replace — the residual add-after-delete failure leaves the
        // source absent until the next replace re-lands it (the caller's
        // own retry re-runs this whole method; a re-run is idempotent).
        Document sourceDocument = new Document(
                source.content(),
                Map.of(
                        "visibility", "PUBLIC",
                        "sourceId", source.sourceId(),
                        "sourceType", source.sourceType()
                ));
        List<Document> replacement = splitter.apply(List.of(sourceDocument));

        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        vectorStore.delete(filters.and(
                filters.eq("visibility", "PUBLIC"),
                filters.eq("sourceId", source.sourceId())
        ).build());
        vectorStore.add(replacement);
    }

    public void deletePublicSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        vectorStore.delete(filters.and(
                filters.eq("visibility", "PUBLIC"),
                filters.eq("sourceId", sourceId)
        ).build());
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
