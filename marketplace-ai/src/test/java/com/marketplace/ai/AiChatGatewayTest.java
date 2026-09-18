package com.marketplace.ai;

import com.marketplace.shared.api.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The gateway's two honest states: OFF (no ChatModel bound — the default
 * {@code spring.ai.model.chat=none}) answers 503 SU-001 without touching any
 * provider; ON delegates one user turn through the documented
 * {@code ChatClient.create} factory around the bound model. Constructor
 * shapes below are the Spring AI 2.0.1 API itself (verified against the
 * resolved spring-ai-model jar), not test doubles of framework behavior.
 * The OFF detail is proven environment-sourced: whatever the selector
 * actually holds (unset, none, or an unmatched value) is what the message
 * reports — never an assumed literal.
 */
@ExtendWith(MockitoExtension.class)
class AiChatGatewayTest {

    /** Minimal in-memory ChatModel: canned answer, no network, no threads. */
    static class FakeChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    @Mock
    ObjectProvider<ChatModel> models;

    private AiChatGateway gatewayWithSelector(String selectorValue) {
        MockEnvironment environment = new MockEnvironment();
        if (selectorValue != null) {
            environment.setProperty(AiChatGateway.CHAT_SELECTOR_PROPERTY, selectorValue);
        }
        return new AiChatGateway(models, environment);
    }

    @Test
    void capabilityOffWhenNoChatModelBound() {
        AiChatGateway gateway = gatewayWithSelector("none");

        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> gateway.chat("hi"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getMessage())
                            .contains(AiChatGateway.CHAT_SELECTOR_PROPERTY + "=none")
                            .contains("google-genai | deepseek");
                });
    }

    /**
     * Regression for the CodeRabbit round-2 finding: the OFF detail must
     * report the selector's actually configured value, never a hardcoded
     * {@code =none} — an unmatched value binds no ChatModel either, and the
     * operator reading the 503 needs the real state.
     */
    @Test
    void offDetailReportsTheActualSelectorValueNotAnAssumedNone() {
        AiChatGateway gateway = gatewayWithSelector("some-unmatched-value");

        assertThatThrownBy(() -> gateway.chat("hi"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex -> {
                    assertThat(ex.getMessage())
                            .contains(AiChatGateway.CHAT_SELECTOR_PROPERTY + "=some-unmatched-value")
                            .doesNotContain("=none)");
                });
    }

    @Test
    void offDetailReportsUnsetWhenSelectorHasNoValue() {
        AiChatGateway gateway = gatewayWithSelector(null);

        assertThatThrownBy(() -> gateway.chat("hi"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getMessage()).contains("(unset)"));
    }

    @Test
    void delegatesToChatClientWhenBound() {
        when(models.getIfAvailable()).thenReturn(new FakeChatModel());
        AiChatGateway gateway = gatewayWithSelector("google-genai");

        assertThat(gateway.available()).isTrue();
        assertThat(gateway.chat("hi")).isEqualTo("hi");
    }
}
