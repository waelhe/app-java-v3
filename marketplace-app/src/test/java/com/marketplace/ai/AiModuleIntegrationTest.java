package com.marketplace.ai;

import com.marketplace.shared.api.ServiceUnavailableException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI foundation OFF state on the real application context: the test profile
 * binds {@code spring.ai.model.chat=none}, so no provider auto-configuration
 * runs and no {@code ChatModel} bean exists — yet the context boots cleanly,
 * the gateway reports the capability OFF, and any call answers 503 SU-001
 * instead of failing startup (the PSP/MAIL house gate). The auto-configured
 * {@code ChatClient.Builder} definition is deliberately never touched here:
 * with zero models bound it is un-instantiable by framework design (its
 * factory method requires a {@code ChatModel}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiModuleIntegrationTest {

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
    void contextBootsWithCapabilityOff() {
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> gateway.chat("hi"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
    }
}
