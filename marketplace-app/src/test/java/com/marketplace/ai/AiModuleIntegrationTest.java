package com.marketplace.ai;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
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
    void capabilityOffKeepsChatDisabledWhileOfficialSessionStorageIsAutoConfigured() {
        assertThat(context.getBeansOfType(AiChatGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(AiKnowledgeGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(AiKnowledgeEntryEventListener.class)).isEmpty();
        assertThat(context.getBeansOfType(SessionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(SessionRepository.class)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ai_session'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ai_session_event'",
                Integer.class)).isEqualTo(1);
    }
}
