package com.marketplace.ai.autoconfigure;

import org.springaicommunity.typesafe.advisor.JevSelfRefineAdvisor;
import org.springaicommunity.typesafe.judge.JevEvaluator;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Adapts the configured TypeSafe judge to Spring AI's evaluation and call-advisor SPIs.
 *
 * <p>This is deliberately ordered after {@link AiAutoConfiguration}, which contributes
 * the policy-configured {@link JevJudge}. Class-level bean conditions avoid evaluating
 * optional adapters before their judge exists; the self-refine adapter has its own
 * property so standalone evaluation does not imply chat retries.</p>
 */
@AutoConfiguration(after = AiAutoConfiguration.class)
@ConditionalOnClass({JevEvaluator.class, JevSelfRefineAdvisor.class, Evaluator.class})
@ConditionalOnBean(JevJudge.class)
@ConditionalOnProperty(
        prefix = "marketplace.ai.typesafe.judge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TypeSafeSelfRefineProperties.class)
public class TypeSafeJudgeAdaptersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Evaluator.class)
    Evaluator jevEvaluator(JevJudge judge) {
        return new JevEvaluator(judge);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "marketplace.ai.typesafe.self-refine", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    JevSelfRefineAdvisor jevSelfRefineAdvisor(
            JevJudge judge, TypeSafeSelfRefineProperties properties) {
        return JevSelfRefineAdvisor.builder()
                .judge(judge)
                .maxRepeatAttempts(properties.getMaxRepeatAttempts())
                .failOnExhaustedAttempts(properties.isFailOnExhaustedAttempts())
                .build();
    }
}
