package com.marketplace.ai.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSafeToolSearchAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    TypeSafeAutoConfiguration.class,
                    AiAutoConfiguration.class));

    @Test
    void officialToolSearchAdvisorRemainsOptIn() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ToolSearchToolCallingAdvisor.class));
    }

    @Test
    void createsTheOfficialJevToolIndexThroughSpringAiToolCallingAdvisorWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.tool-search.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ToolSearchToolCallingAdvisor.class));
    }

    @Test
    void toolSearchDoesNotActivateWithoutATypeSafeCredential() {
        contextRunner
                .withPropertyValues("marketplace.ai.typesafe.tool-search.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ToolSearchToolCallingAdvisor.class));
    }
}
