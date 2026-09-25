package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of the documented multi-model selector: with both provider
 * starters present, {@code spring.ai.model.chat=deepseek} activates exactly
 * that provider's auto-configuration — one {@code ChatModel}, gateway
 * available. Mirrors {@link AiProviderSelectionIntegrationTest} (the
 * google-genai half) so a DeepSeek auto-configuration or property-wiring
 * regression cannot pass unnoticed (CodeRabbit incremental review finding,
 * round 3). No network is touched (the fake key only satisfies binding).
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=deepseek",
        "spring.ai.deepseek.api-key=test-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiDeepSeekProviderSelectionIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent (AiProviderSelectionIntegrationTest)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AiChatGateway gateway;

    @Test
    void selectorActivatesExactlyOneProvider() {
        assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
        assertThat(gateway.available()).isTrue();
    }
}
