package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.AiQueryUnderstanding;
import com.marketplace.ai.MarketplaceSearchTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiAutoConfigurationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void composesOptionalOfficialExtensionsWhenPresent() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        MarketplaceSearchTools searchTool = mock(MarketplaceSearchTools.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<MarketplaceSearchTools> searchTools = mock(ObjectProvider.class);
        ObjectProvider<VectorStore> vectorStores = mock(ObjectProvider.class);

        doAnswer(invocation -> {
            ((Consumer<MarketplaceSearchTools>) invocation.getArgument(0)).accept(searchTool);
            return null;
        }).when(searchTools).ifAvailable(any());

        doAnswer(invocation -> {
            ((Consumer<VectorStore>) invocation.getArgument(0)).accept(vectorStore);
            return null;
        }).when(vectorStores).ifAvailable(any());

        AiChatGateway gateway = new AiAutoConfiguration()
                .aiChatGateway(builder, chatMemory, searchTools, vectorStores);

        assertThat(gateway).isNotNull();
        verify(searchTools).ifAvailable(any());
        verify(vectorStores).ifAvailable(any());
    }

    @Test
    void createsQueryUnderstandingFromSpringAiManagedBuilder() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        AiQueryUnderstanding understanding =
                new AiAutoConfiguration().aiQueryUnderstanding(builder);

        assertThat(understanding).isNotNull();
    }
}
