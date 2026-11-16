package com.marketplace.ai.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.advisor.JevGuardrailAdvisor;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSafeGuardrailAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TypeSafeAutoConfiguration.class,
                    AiAutoConfiguration.class));

    @Test
    void guardrailsRemainOptInOutsideTheTypesafeProfile() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevGuardrailAdvisor.class));
    }

    @Test
    void guardrailsUseTheOfficialAdvisorWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.guardrails.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .hasSingleBean(JevGuardrailAdvisor.class));
    }

    @Test
    void enablingGuardrailsWithoutATypeSafeKeyDoesNotCreateAnAdvisor() {
        contextRunner
                .withPropertyValues("marketplace.ai.typesafe.guardrails.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevGuardrailAdvisor.class));
    }
}
