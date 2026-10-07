package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiAutoConfigurationTest {

    @Test
    void createsGatewayFromSpringAiManagedBuilder() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        AiChatGateway gateway = new AiAutoConfiguration()
                .aiChatGateway(builder, chatMemory);

        assertThat(gateway).isNotNull();
    }
}
