package com.marketplace.ai.autoconfigure;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.toolsearch.JevToolIndex;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Provides the TypeSafe implementation of Spring AI's ToolIndex SPI.
 *
 * <p>The Spring AI starter remains responsible for constructing the
 * ToolSearchToolCallingAdvisor and integrating it into ChatClient auto-configuration.
 * This ordered configuration contributes only the community ToolIndex before Spring AI
 * chooses its standard fallback index.</p>
 */
@AutoConfiguration(
        afterName = "org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration",
        beforeName = "org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration")
@ConditionalOnClass({TypeSafeClient.class, JevToolIndex.class})
public class TypeSafeToolSearchAutoConfiguration {

    @Bean
    @ConditionalOnBean(TypeSafeClient.class)
    @ConditionalOnProperty(
            prefix = "spring.ai.chat.client.tool-search-advisor", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ToolIndex.class)
    ToolIndex jevToolIndex(TypeSafeClient typeSafeClient) {
        return JevToolIndex.builder(typeSafeClient).build();
    }
}
