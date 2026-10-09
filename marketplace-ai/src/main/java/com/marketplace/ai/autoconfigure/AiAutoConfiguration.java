package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.AiKnowledgeGateway;
import com.marketplace.ai.AiQueryUnderstanding;
import com.marketplace.ai.AiSessionExpirationCleanup;
import com.marketplace.ai.MarketplaceSearchTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        after = ChatClientAutoConfiguration.class,
        afterName = {
                "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration",
                "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
                "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration",
                "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration",
                "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                "org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration",
                "org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration"
        })
@ConditionalOnClass(ChatClient.class)
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnBean({ChatClient.Builder.class, SessionService.class})
    @ConditionalOnMissingBean
    AiChatGateway aiChatGateway(
            ChatClient.Builder builder,
            SessionService sessionService,
            ObjectProvider<MarketplaceSearchTools> searchTools,
            ObjectProvider<VectorStore> vectorStores) {

        ChatClient.Builder configured = builder.defaultAdvisors(
                SessionMemoryAdvisor.builder(sessionService).build());
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
    @ConditionalOnBean(SessionService.class)
    @ConditionalOnMissingBean
    AiSessionExpirationCleanup aiSessionExpirationCleanup(SessionService sessionService) {
        return new AiSessionExpirationCleanup(sessionService);
    }

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
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
