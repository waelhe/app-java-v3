package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documented multi-model selector on this classpath: with both provider
 * starters present, {@code spring.ai.model.chat=google-genai} activates
 * exactly that provider's auto-configuration — one {@code ChatModel},
 * gateway available. No network is touched (bean creation is lazy; the fake
 * key only satisfies binding).
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=google-genai",
        "spring.ai.google.genai.api-key=test-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiProviderSelectionIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent (CatalogSearchFullTextIntegrationTest)
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
