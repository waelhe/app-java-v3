package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiChatGatewayTest {

    @Test
    void delegatesToTheAutoConfiguredChatClientAndReturnsFullResponse() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.AdvisorSpec advisors = mock(ChatClient.AdvisorSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);

        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("hi"))));

        when(client.prompt()).thenReturn(request);
        when(request.advisors(org.mockito.ArgumentMatchers.any())).thenReturn(request);
        when(request.user("hi")).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.chatResponse()).thenReturn(response);

        AiChatGateway gateway = new AiChatGateway(client);

        assertThat(gateway.chat("conversation-1", "hi")).isSameAs(response);
    }
}
