package com.marketplace.ai.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TypeSafeRagAdvisorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TypeSafeAutoConfiguration.class,
                    AiAutoConfiguration.class))
            .withBean(VectorStore.class, () -> mock(VectorStore.class));

    @Test
    void typeSafeRagIsNotEnabledByDefaultEvenWhenTheClientIsConfigured() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .doesNotHaveBean(RetrievalAugmentationAdvisor.class));
    }

    @Test
    void optedInProfileComposesTheOfficialRagAdvisorAndTypeSafeFilter() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.rag.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .hasSingleBean(RetrievalAugmentationAdvisor.class));
    }

    @Test
    void enablingTheFeatureWithoutATypeSafeKeyDoesNotCreateClientOrAdvisor() {
        contextRunner
                .withPropertyValues("marketplace.ai.typesafe.rag.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TypeSafeClient.class)
                        .doesNotHaveBean(RetrievalAugmentationAdvisor.class));
    }
}
