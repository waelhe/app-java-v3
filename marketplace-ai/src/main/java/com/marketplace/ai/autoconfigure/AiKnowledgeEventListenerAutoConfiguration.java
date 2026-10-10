package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiKnowledgeEntryEventListener;
import com.marketplace.ai.AiKnowledgeGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;

@AutoConfiguration(after = AiAutoConfiguration.class)
@ConditionalOnClass(ApplicationModuleListener.class)
@ConditionalOnBean(AiKnowledgeGateway.class)
public class AiKnowledgeEventListenerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AiKnowledgeEntryEventListener aiKnowledgeEntryEventListener(AiKnowledgeGateway knowledgeGateway) {
        return new AiKnowledgeEntryEventListener(knowledgeGateway);
    }
}
