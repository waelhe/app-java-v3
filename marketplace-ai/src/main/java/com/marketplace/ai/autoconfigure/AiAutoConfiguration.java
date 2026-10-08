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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = {
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
        "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
        // The provider ChatModel sources (the @ConditionalOnBean evaluation-
        // order rule: a condition can only match against bean definitions
        // processed so far — without these edges the gateway's
        // ConditionalOnBean(ChatModel) can evaluate before the selected
        // provider registers its ChatModel, and the gateway bean silently
        // never exists; the CodeRabbit-measured gap, the same class the
        // 45d3fdc2 fix closed for the memory/client sources).
        "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration",
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
@ConditionalOnClass(ChatClient.class)
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnBean({ChatModel.class, ChatMemory.class, ChatClient.Builder.class})
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
                                .topK(6)
                                .similarityThreshold(0.75d)
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
