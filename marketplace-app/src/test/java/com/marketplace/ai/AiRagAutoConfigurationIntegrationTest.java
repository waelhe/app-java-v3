package com.marketplace.ai;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding.text=google-genai",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "spring.ai.vectorstore.pgvector.initialize-schema=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiRagAutoConfigurationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private ApplicationContext context;

    @Test
    void officialEmbeddingAndPgVectorAutoConfigurationComposeTheKnowledgeGateway() {
        assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(VectorStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(AiKnowledgeGateway.class)).hasSize(1);
        assertThat(context.getBeansOfType(AiChatGateway.class)).isEmpty();
    }
}
