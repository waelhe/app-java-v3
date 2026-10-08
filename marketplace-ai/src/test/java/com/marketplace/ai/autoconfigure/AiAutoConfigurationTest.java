package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.MarketplaceSearchTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiAutoConfigurationTest {
    @Test
    void createsGatewayFromSpringAiManagedBuilder() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ObjectProvider<MarketplaceSearchTools> searchTools = mock(ObjectProvider.class);
        ObjectProvider<VectorStore> vectorStores = mock(ObjectProvider.class);

        AiChatGateway gateway = new AiAutoConfiguration()
                .aiChatGateway(builder, chatMemory, searchTools, vectorStores);

        assertThat(gateway).isNotNull();
    }
}
