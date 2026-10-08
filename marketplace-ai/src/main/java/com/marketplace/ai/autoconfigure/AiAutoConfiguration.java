package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.AiKnowledgeGateway;
import com.marketplace.ai.AiQueryUnderstanding;
import com.marketplace.ai.MarketplaceSearchTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {
        ChatClientAutoConfiguration.class,
        ChatMemoryAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        DeepSeekChatAutoConfiguration.class
})
@ConditionalOnClass(ChatClient.class)
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnBean({ChatModel.class, ChatClient.Builder.class, ChatMemory.class})
    @ConditionalOnMissingBean
    AiChatGateway aiChatGateway(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            ObjectProvider<MarketplaceSearchTools> searchTools,
            ObjectProvider<VectorStore> vectorStores) {

        ChatClient.Builder configured = builder.defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build());

        searchTools.ifAvailable(configured::defaultTools);

        vectorStores.ifAvailable(vectorStore -> configured.defaultAdvisors(
                QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                                .filterExpression("visibility == 'PUBLIC'")
                                .build())
                        .build()));

        return new AiChatGateway(configured.build());
    }

    @Bean
    @ConditionalOnBean({ChatModel.class, ChatClient.Builder.class})
    @ConditionalOnMissingBean
    AiQueryUnderstanding aiQueryUnderstanding(ChatClient.Builder builder) {
        return new AiQueryUnderstanding(builder);
    }

    @Bean
    @ConditionalOnBean(VectorStore.class)
    @ConditionalOnMissingBean
    AiKnowledgeGateway aiKnowledgeGateway(VectorStore vectorStore) {
        return new AiKnowledgeGateway(vectorStore);
    }
}
