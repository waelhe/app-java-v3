package com.marketplace.ai;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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
        "spring.ai.model.chat=google-genai",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.chat.client.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiChatClientDisabledIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private ApplicationContext context;

    @Test
    void officialChatClientDisablementPreventsApplicationAiChatBeans() {
        assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatClient.Builder.class)).isEmpty();
        assertThat(context.getBeansOfType(AiChatGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(AiQueryUnderstanding.class)).isEmpty();
    }
}
