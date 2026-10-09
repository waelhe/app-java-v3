package com.marketplace.ai;

import test.config.IntegrationContainers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "spring.ai.model.embedding.text=google-genai",
        "spring.ai.vectorstore.pgvector.initialize-schema=false"
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

    @Autowired
    private SessionService sessionService;

    @Test
    void chatOffKeepsOfficialSessionAndEmbeddingVectorStorageAutoConfigured() {
        assertThat(context.getBeansOfType(AiChatGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(AiKnowledgeGateway.class)).hasSize(1);
        assertThat(context.getBeansOfType(AiKnowledgeEntryEventListener.class)).hasSize(1);
        assertThat(context.getBeansOfType(SessionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(SessionRepository.class)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ai_session'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ai_session_event'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void officialJdbcSessionStoreRoundTripsToolCallsAndToolResults() {
        UUID userId = UUID.randomUUID();
        Session session = sessionService.create(CreateSessionRequest.builder()
                .userId(userId.toString())
                .metadata("title", "Tool history round-trip")
                .build());

        String arguments = "{" + "\"query\"" + ":" + "\"parks\"" + "}";
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "search_marketplace_listings", arguments);
        ToolResponseMessage.ToolResponse toolResult = new ToolResponseMessage.ToolResponse(
                "call-1", "search_marketplace_listings", "2 public listings");

        sessionService.appendMessage(session.id(), new UserMessage("Find parks"));
        sessionService.appendMessage(session.id(), AssistantMessage.builder()
                .content("I will search.")
                .toolCalls(List.of(toolCall))
                .build());
        sessionService.appendMessage(session.id(), ToolResponseMessage.builder()
                .responses(List.of(toolResult))
                .build());

        List<SessionEvent> events = sessionService.getEvents(session.id(), EventFilter.all());

        assertThat(events).hasSize(3);
        assertThat(events).extracting(SessionEvent::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        assertThat(((AssistantMessage) events.get(1).getMessage()).getToolCalls())
                .containsExactly(toolCall);
        assertThat(((ToolResponseMessage) events.get(2).getMessage()).getResponses())
                .containsExactly(toolResult);

        sessionService.delete(session.id());
        assertThat(sessionService.findById(session.id())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ai_session_event where session_id = ?",
                Integer.class, session.id())).isZero();
    }


}
