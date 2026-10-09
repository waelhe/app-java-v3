package com.marketplace.ai;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "spring.ai.model.embedding.text=none"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void capabilityOffMeansMarketplaceAiAutoConfigurationDoesNotCreateApplicationBeans() {
        assertThat(context.getBeansOfType(AiChatGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMemory.class)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'spring_ai_chat_memory'",
                Integer.class)).isEqualTo(1);
    }
}
