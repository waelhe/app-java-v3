package com.marketplace.ai.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the official TypeSafe starter is opt-in and that enabling it uses
 * its documented Spring Boot property without making an external API call.
 */
class TypeSafeStarterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class));

    @Test
    void backsOffWithoutApiKey() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(TypeSafeClient.class));
    }

    @Test
    void createsTheOfficialClientWhenApiKeyIsConfigured() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-typesafe-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(TypeSafeClient.class);
                    assertThat(context.getBean(TypeSafeClient.class).defaultModel())
                            .isEqualTo(TypeSafeModels.JEV_LATEST);
                });
    }
}
