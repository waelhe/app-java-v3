package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.JevModelRouter;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TypeSafeModelRouterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TypeSafeAutoConfiguration.class,
                    AiAutoConfiguration.class))
            // Register the mock under its CONCRETE type: the router beans guard on
            // @ConditionalOnBean(GoogleGenAiChatModel.class), and ConditionalOnBean
            // matches the bean DEFINITION's type — a ChatModel-typed definition never
            // satisfies it, so the router (correctly) stays away. Production registers
            // the real model with its concrete type, so the concrete-typed mock is the
            // faithful wiring under test (Spring Boot reference: ConditionalOnBean).
            .withBean(GoogleGenAiChatModel.class, () -> mock(GoogleGenAiChatModel.class));

    @Test
    void routingRemainsOffWithoutTheTypesafeProfileProperty() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevModelRouter.class));
    }

    @Test
    void routingUsesTheConfiguredProviderWhenOptedInWithoutCallingTheService() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.model-routing.enabled=true",
                        "marketplace.ai.typesafe.model-routing.google.fast-model=gemini-test-fast",
                        "marketplace.ai.typesafe.model-routing.google.capable-model=gemini-test-capable")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .hasSingleBean(JevModelRouter.class));
    }

    @Test
    void usesProviderSpecificDefaultModelsWhenRoutingIsEnabledWithoutOverrides() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.model-routing.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevModelRouter.class);
                });
    }

    @Test
    void anUnconfiguredTypesafeClientDoesNotCreateTheRouter() {
        contextRunner
                .withPropertyValues("marketplace.ai.typesafe.model-routing.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevModelRouter.class));
    }
}
