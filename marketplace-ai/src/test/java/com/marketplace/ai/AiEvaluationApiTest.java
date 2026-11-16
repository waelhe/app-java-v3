package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.evaluation.Evaluator;
import static org.assertj.core.api.Assertions.assertThat;

class AiEvaluationApiTest {
    @Test
    void springAiEvaluationApisAreAvailableForRagQualityGates() {
        assertThat(Evaluator.class).isAssignableFrom(RelevancyEvaluator.class);
        assertThat(Evaluator.class).isAssignableFrom(FactCheckingEvaluator.class);
    }
}
