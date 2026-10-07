package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.web.AiController;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the marketplace AI surface.
 *
 * <p>The configuration is outside the application's component-scan package by design.
 * It participates only through Spring Boot's auto-configuration imports mechanism.
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
        "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration"
})
@ConditionalOnClass({ChatClient.class, ChatMemory.class})
@ConditionalOnBean({ChatModel.class, ChatMemory.class})
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AiChatGateway aiChatGateway(ChatClient.Builder builder, ChatMemory chatMemory) {
        return new AiChatGateway(
                builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .build()
        );
    }

    @Bean
    @ConditionalOnBean(AiChatGateway.class)
    @ConditionalOnMissingBean
    AiController aiController(AiChatGateway aiChatGateway) {
        return new AiController(aiChatGateway);
    }
}
