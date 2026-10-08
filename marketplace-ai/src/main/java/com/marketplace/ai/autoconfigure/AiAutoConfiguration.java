package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
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
 *
 * <p><b>W6 (the ordering law, measured on the 2.0.1 AutoConfiguration.imports):</b>
 * {@code @ConditionalOnBean} "can only match against bean definitions that have
 * been processed by the application context so far" — the Spring Boot reference's
 * own caveat, resolved for auto-configurations by ordering: this class declares
 * {@code afterName} for EVERY auto-configuration that registers a bean its
 * conditions inspect. The three contributing classes on this classpath are the
 * two provider chat auto-configurations (either one registers the
 * {@code ChatModel}, selected by {@code spring.ai.model.chat}) and the JDBC
 * chat-memory repository (registers the {@code ChatMemory}); the previous
 * afterName listed none of the three, so alphabetical class-name ordering
 * evaluated this configuration BEFORE them — the conditions inspected bean
 * definitions that did not exist yet, and the gateway silently never
 * registered even with a live ChatModel and ChatMemory (the CI-measured
 * failure: ChatModel=1, ChatMemory=1, AiChatGateway=0). {@code afterName} is
 * by NAME precisely so absent classes are simply not ordered — the providers
 * are deployment selections; whichever is present now precedes this class.
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
        "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
        "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration"
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

}
