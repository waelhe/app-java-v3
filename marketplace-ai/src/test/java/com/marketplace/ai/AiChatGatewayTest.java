package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;

class AiChatGatewayTest {

    @Test
    void exposesTheOfficialCallContentResultForTheHttpAdapter() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("assistant answer");

        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThat(gateway.answer(UUID.randomUUID(), UUID.randomUUID().toString(), "hello"))
                .isEqualTo("assistant answer");
    }

    @Test
    void delegatesToSpringAiAndValidatesTheResponseEnvelope() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(chatClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(response);
        when(response.chatResponse()).thenReturn(mock(org.springframework.ai.chat.model.ChatResponse.class));

        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThat(gateway.chat(UUID.randomUUID(), UUID.randomUUID().toString(), "hello")).isSameAs(response);
    }

    @Test
    void appliesTheJevSelectedModelToTheSpringAiChatCall() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        TypeSafeClient typeSafeClient = mock(TypeSafeClient.class);
        when(chatClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.options(any(GoogleGenAiChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("routed answer");
        when(typeSafeClient.systemOne(anyMap(), anyMap())).thenReturn(new SystemOneResponse(
                "jev-latest",
                Map.<String, Answer>of("model_tier",
                        new ChoiceAnswer("FAST", Map.of("FAST", 0.9d, "CAPABLE", 0.1d), 0.9d)),
                null));

        JevModelRouter router = new JevModelRouter(
                typeSafeClient, mock(GoogleGenAiChatModel.class),
                "gemini-3.5-flash-lite", "gemini-3.8-flash");
        AiChatGateway gateway = new AiChatGateway(chatClient, router);

        assertThat(gateway.answer(UUID.randomUUID(), UUID.randomUUID().toString(), "hello"))
                .isEqualTo("routed answer");

        org.mockito.ArgumentCaptor<GoogleGenAiChatOptions.Builder> options =
                org.mockito.ArgumentCaptor.forClass(GoogleGenAiChatOptions.Builder.class);
        verify(request).options(options.capture());
        assertThat(options.getValue().build().getModel()).isEqualTo("gemini-3.5-flash-lite");
    }

    @Test
    void delegatesToOfficialStreamingContentPath() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.stream()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(Flux.just("Hello", " world"));

        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThat(gateway.streamAnswer(UUID.randomUUID(), UUID.randomUUID().toString(), "hello")
                .collectList().block()).containsExactly("Hello", " world");
    }

    @Test
    void sendsCompleteCallsToTheGuardedClient() {
        ChatClient streamingClient = mock(ChatClient.class);
        ChatClient guardedClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(guardedClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("safe answer");

        AiChatGateway gateway = new AiChatGateway(streamingClient, guardedClient, null);
        assertThat(gateway.answer(UUID.randomUUID(), UUID.randomUUID().toString(), "hello"))
                .isEqualTo("safe answer");

        verify(guardedClient).prompt();
        verify(streamingClient, never()).prompt();
    }

    @Test
    void buffersSseOutputThroughTheGuardedCallWhenJevGuardrailsAreConfigured() {
        ChatClient streamingClient = mock(ChatClient.class);
        ChatClient guardedClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(guardedClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("verified answer");

        AiChatGateway gateway = new AiChatGateway(streamingClient, guardedClient, null);

        assertThat(gateway.streamAnswer(UUID.randomUUID(), UUID.randomUUID().toString(), "hello")
                .collectList().block()).containsExactly("verified answer");

        verify(guardedClient).prompt();
        verify(request).call();
        verify(request, never()).stream();
        verify(streamingClient, never()).prompt();
    }

    @Test
    void buffersSseWhenCompleteCallPoliciesAreEnabledEvenIfTheBuilderReusesTheClient() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("accepted answer");

        AiChatGateway gateway = new AiChatGateway(chatClient, chatClient, null, true);

        assertThat(gateway.streamAnswer(UUID.randomUUID(), UUID.randomUUID().toString(), "hello")
                .collectList().block()).containsExactly("accepted answer");

        verify(request).call();
        verify(request, never()).stream();
    }

    @Test
    void buffersRawSseResponsesThroughTheGuardedCallWhenJevGuardrailsAreConfigured() {
        ChatClient streamingClient = mock(ChatClient.class);
        ChatClient guardedClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(guardedClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(response);
        when(response.chatResponse()).thenReturn(mock(org.springframework.ai.chat.model.ChatResponse.class));

        AiChatGateway gateway = new AiChatGateway(streamingClient, guardedClient, null);

        assertThat(gateway.stream(UUID.randomUUID(), UUID.randomUUID().toString(), "hello")
                .collectList().block()).containsExactly(response);

        verify(guardedClient).prompt();
        verify(request).call();
        verify(request, never()).stream();
        verify(streamingClient, never()).prompt();
    }

    @Test
    void rejectsBlankSessionId() {
        AiChatGateway gateway = new AiChatGateway(mock(ChatClient.class));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gateway.answer(UUID.randomUUID(), " ", "hello"))
                .withMessage("conversationId must not be blank");
    }

    @Test
    void rejectsNullChatResponse() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        stubRequest(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(null);

        AiChatGateway gateway = new AiChatGateway(chatClient);
        assertThatIllegalStateException()
                .isThrownBy(() -> gateway.chat(UUID.randomUUID(), UUID.randomUUID().toString(), "hello"))
                .withMessage("Spring AI returned an empty chat response");
    }

    @Test
    void theD4BreakerPinsTheChatCrossing() throws Exception {
        // D.4's surviving half (union 2026-10-09): the synchronous external
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
        // would duplicate the user turn in the Spring AI Session history. Pin
        // its absence so it never silently returns.
        assertThat(chat.isAnnotationPresent(io.github.resilience4j.retry.annotation.Retry.class))
                .as("chat() must NOT wear @Retry — session-history writes make the call non-idempotent")
                .isFalse();
    }

    // The pre-#522 scopeConversation derivation pin retired WITH its design:
    // main's completed contract owns conversation state through Spring AI
    // Session (opaque server-issued IDs + the advisor's official ownership
    // check), so the derived 36-char key has no remaining call site.

    private static void stubRequest(ChatClient.ChatClientRequestSpec request) {
        when(request.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(request);
        when(request.toolContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(request);
    }
}
