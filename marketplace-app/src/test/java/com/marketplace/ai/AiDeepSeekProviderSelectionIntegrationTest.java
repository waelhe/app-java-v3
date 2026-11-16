package com.marketplace.ai;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
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
        "spring.ai.model.chat=deepseek",
        "spring.ai.chat.client.enabled=true",
        "spring.ai.deepseek.api-key=test-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiDeepSeekProviderSelectionIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private ApplicationContext context;

    @Test
    void selectorActivatesProviderAndOfficialAutoConfigurationBuildsTheChatStack() {
        assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatClient.Builder.class)).hasSize(1);
        assertThat(context.getBeansOfType(SessionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(SessionRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(AiChatGateway.class)).hasSize(1);
    }
}
