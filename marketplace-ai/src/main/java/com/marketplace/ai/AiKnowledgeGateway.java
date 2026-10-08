package com.marketplace.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AiKnowledgeGateway {

    private final VectorStore vectorStore;

    public AiKnowledgeGateway(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
    }

    public void replacePublicSource(AiKnowledgeSource source) {
        Objects.requireNonNull(source, "source must not be null");

        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        vectorStore.delete(filters.and(
                filters.eq("visibility", "PUBLIC"),
                filters.eq("sourceId", source.sourceId())
        ).build());

        vectorStore.add(List.of(new Document(
                source.content(),
                Map.of(
                        "visibility", "PUBLIC",
                        "sourceId", source.sourceId(),
                        "sourceType", source.sourceType()
                )
        )));
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
