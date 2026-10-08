package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiKnowledgeGatewayTest {
    @Test
    void replacesPublicSourceUsingVectorStore() {
        VectorStore vectorStore = mock(VectorStore.class);
        new AiKnowledgeGateway(vectorStore).replacePublicSource(
                new AiKnowledgeGateway.AiKnowledgeSource("faq-1", "faq", "Public marketplace FAQ"));
        verify(vectorStore).add(org.mockito.ArgumentMatchers.argThat(
                documents -> documents.size() == 1
                        && "PUBLIC".equals(documents.get(0).getMetadata().get("visibility"))
                        && "faq-1".equals(documents.get(0).getMetadata().get("sourceId"))));
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
        verify(vectorStore).delete(org.mockito.ArgumentMatchers.any(
                org.springframework.ai.vectorstore.filter.Filter.Expression.class));
    }
}
