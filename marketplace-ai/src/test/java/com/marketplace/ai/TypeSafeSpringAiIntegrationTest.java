package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSafeSpringAiIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class));

    @Test
    void officialStarterDoesNotCreateClientWithoutAnApiKey() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(TypeSafeClient.class));
    }

    @Test
    void officialStarterCreatesClientWithAConfiguredTestKeyWithoutCallingTheNetwork() {
        contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(TypeSafeClient.class);
                    assertThat(context).hasSingleBean(TypeSafeAutoConfiguration.TypeSafeEndpoints.class);
                });
    }

    @Test
    void officialSpringAiIntegrationModulesAreOnTheClasspath() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(ClassUtils.isPresent(
                "org.springaicommunity.typesafe.judge.JevJudge", loader)).isTrue();
        assertThat(ClassUtils.isPresent(
                "org.springaicommunity.typesafe.advisor.JevGuardrailAdvisor", loader)).isTrue();
        assertThat(ClassUtils.isPresent(
                "org.springaicommunity.typesafe.rag.JevDocumentReranker", loader)).isTrue();
        assertThat(ClassUtils.isPresent(
                "org.springaicommunity.typesafe.toolsearch.JevToolIndex", loader)).isTrue();
    }
}
