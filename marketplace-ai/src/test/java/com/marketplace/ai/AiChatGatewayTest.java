package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiChatGatewayTest {
    @Test
    void delegatesToSpringAiAndScopesConversationByUser() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())).thenReturn(request);
        when(request.toolContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(response);
        when(response.chatResponse()).thenReturn(mock(org.springframework.ai.chat.model.ChatResponse.class));
        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThat(gateway.chat(UUID.randomUUID(), "conversation-1", "hello")).isSameAs(response);
    }

    @Test
    void delegatesToOfficialStreamingChatClientPath() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())).thenReturn(request);
        when(request.toolContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(request);
        when(request.stream()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(Flux.just(response));

        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThat(gateway.stream(UUID.randomUUID(), "conversation-1", "hello")
                .collectList().block())
                .containsExactly(response);
    }

    @Test
    void rejectsBlankConversationId() {
        AiChatGateway gateway = new AiChatGateway(mock(ChatClient.class));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gateway.chat(UUID.randomUUID(), " ", "hello"))
                .withMessage("conversationId must not be blank");
    }

    @Test
    void rejectsNullChatResponse() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())).thenReturn(request);
        when(request.toolContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(null);
        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThatIllegalStateException()
                .isThrownBy(() -> gateway.chat(UUID.randomUUID(), "conversation-1", "hello"))
                .withMessage("Spring AI returned an empty chat response");
    }

    @Test
    void theD4BreakerPinsTheChatCrossing() throws Exception {
        // D.4's surviving half (union 2026-10-08): the synchronous external
        // crossing wears the official Resilience4j circuitbreaker annotation,
        // and the class must stay proxyable (non-final) for the aspect's
        // CGLIB proxy — a final class would make the annotation silently
        // inert, the measured trap this pin exists to prevent.
        java.lang.reflect.Method chat = AiChatGateway.class
                .getDeclaredMethod("chat", UUID.class, String.class, String.class);
        io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker breaker =
                chat.getAnnotation(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker.class);
        assertThat(breaker).as("chat() must wear @CircuitBreaker").isNotNull();
        assertThat(breaker.name()).isEqualTo("aiChat");
        assertThat(java.lang.reflect.Modifier.isFinal(AiChatGateway.class.getModifiers()))
                .as("AiChatGateway must stay non-final for the breaker's CGLIB proxy")
                .isFalse();
        // The retry leg retired with the single-turn design: a retried call
        // would duplicate the user turn in ChatMemory. Pin its absence so it
        // never silently returns.
        assertThat(chat.isAnnotationPresent(io.github.resilience4j.retry.annotation.Retry.class))
                .as("chat() must NOT wear @Retry — ChatMemory writes make the call non-idempotent")
                .isFalse();
    }

    @Test
    void scopesConversationDeterministically() {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        // The derived 36-char key: deterministic for the same (user,
        // conversation) pair — the V107-safe shape the raw concatenation
        // could never be (41+ chars against VARCHAR(36)).
        String first = AiChatGateway.scopeConversation(userId, " abc ");
        String second = AiChatGateway.scopeConversation(userId, "abc");
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(36);
        // Per-user isolation: a different user never derives the same row.
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertThat(AiChatGateway.scopeConversation(other, "abc")).isNotEqualTo(first);
    }
}
