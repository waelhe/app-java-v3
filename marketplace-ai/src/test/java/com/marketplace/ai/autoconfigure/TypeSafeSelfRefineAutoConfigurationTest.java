package com.marketplace.ai.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.advisor.JevSelfRefineAdvisor;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevEvaluator;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSafeSelfRefineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TypeSafeAutoConfiguration.class,
                    AiAutoConfiguration.class,
                    TypeSafeJudgeAdaptersAutoConfiguration.class));

    @Test
    void judgeAndSelfRefinementStayOptInOutsideTheTypesafeProfile() {
        contextRunner
                .withPropertyValues("spring.ai.typesafe.api-key=test-only-key")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevJudge.class)
                        .doesNotHaveBean(Evaluator.class)
                        .doesNotHaveBean(JevSelfRefineAdvisor.class));
    }

    @Test
    void usesTheOfficialJevJudgeAndBoundedSelfRefineAdvisorWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.judge.enabled=true",
                        "marketplace.ai.typesafe.self-refine.enabled=true",
                        "marketplace.ai.typesafe.self-refine.max-repeat-attempts=2",
                        "marketplace.ai.typesafe.self-refine.fail-on-exhausted-attempts=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .hasSingleBean(JevJudge.class)
                        .hasSingleBean(Evaluator.class)
                        .hasSingleBean(JevSelfRefineAdvisor.class));
    }

    @Test
    void selfRefinementDoesNotActivateUnlessTheSharedJudgeIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.self-refine.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevSelfRefineAdvisor.class));
    }

    @Test
    void exposesOfficialEvaluatorWithoutEnablingChatSelfRefinement() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.typesafe.api-key=test-only-key",
                        "marketplace.ai.typesafe.judge.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevJudge.class);
                    assertThat(context).hasSingleBean(Evaluator.class);
                    assertThat(context.getBean(Evaluator.class)).isInstanceOf(JevEvaluator.class);
                    assertThat(context).doesNotHaveBean(JevSelfRefineAdvisor.class);
                });
    }

    @Test
    void doesNotCreateJudgeOrAdvisorWithoutATypeSafeClient() {
        contextRunner
                .withPropertyValues(
                        "marketplace.ai.typesafe.judge.enabled=true",
                        "marketplace.ai.typesafe.self-refine.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TypeSafeClient.class)
                        .doesNotHaveBean(JevJudge.class)
                        .doesNotHaveBean(Evaluator.class)
                        .doesNotHaveBean(JevSelfRefineAdvisor.class));
    }
}
