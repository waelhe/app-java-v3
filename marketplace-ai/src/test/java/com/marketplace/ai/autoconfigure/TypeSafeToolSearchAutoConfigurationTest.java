package com.marketplace.ai.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springaicommunity.typesafe.toolsearch.JevToolIndex;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TypeSafeToolSearchAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    TypeSafeAutoConfiguration.class,
                    TypeSafeToolSearchAutoConfiguration.class,
                    ToolSearchAdvisorAutoConfiguration.class,
                    ChatClientAutoConfiguration.class))
            .withBean(ChatModel.class, () -> mock(ChatModel.class));

    @Test
    void officialToolSearchRemainsOptIn() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevToolIndex.class)
                        .doesNotHaveBean(ToolSearchToolCallingAdvisor.Builder.class));
    }

    @Test
    void usesJevToolIndexThroughSpringAIsOfficialAutoConfiguredAdvisorBuilder() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "spring.ai.chat.client.tool-search-advisor.enabled=true",
                        "spring.ai.chat.client.tool-search-advisor.session-id-key-name=chat_memory_conversation_id")
                .run(context -> {
                    assertThat(context).hasSingleBean(TypeSafeClient.class);
                    assertThat(context).hasSingleBean(ToolIndex.class);
                    assertThat(context.getBean(ToolIndex.class)).isInstanceOf(JevToolIndex.class);
                    assertThat(context).hasSingleBean(ToolCallingAdvisor.Builder.class);
                    assertThat(context.getBean(ToolCallingAdvisor.Builder.class))
                            .isInstanceOf(ToolSearchToolCallingAdvisor.Builder.class);
                    assertThat(context).hasSingleBean(ChatClient.Builder.class);
                });
    }

    @Test
    void standardSearchStarterUsesItsOfficialFallbackIndexWithoutTypeSafeKey() {
        contextRunner
                .withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TypeSafeClient.class);
                    assertThat(context).doesNotHaveBean(JevToolIndex.class);
                    assertThat(context).hasSingleBean(ToolIndex.class);
                    assertThat(context.getBean(ToolIndex.class))
                            .isNotInstanceOf(JevToolIndex.class);
                });
    }
}
