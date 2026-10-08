package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiKnowledgeGatewayTest {

    @Test
    void replacesPublicSourceWithOfficialTokenChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
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

    @Test
    void deletesPublicSourceThroughMetadataFilter() {
        VectorStore vectorStore = mock(VectorStore.class);
        new AiKnowledgeGateway(vectorStore).deletePublicSource("faq-1");

        verify(vectorStore).delete(any(
                org.springframework.ai.vectorstore.filter.Filter.Expression.class));
    }
}
