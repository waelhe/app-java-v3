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

    @Test
    void capabilityOffWhenNoChatModelBound() {
        AiChatGateway gateway = new AiChatGateway(models);

        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> gateway.chat("hi"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void delegatesToChatClientWhenBound() {
        when(models.getIfAvailable()).thenReturn(new FakeChatModel());
        AiChatGateway gateway = new AiChatGateway(models);

        assertThat(gateway.available()).isTrue();
        assertThat(gateway.chat("hi")).isEqualTo("hi");
    }
}
