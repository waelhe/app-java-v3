package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the provider-gate semantics of {@link StripeChannelConfiguredCondition}
 * — the multi-key "every credential non-blank" contract (mirrors
 * MediaStorageConfiguredConditionTest, the media layer's proven shape).
 */
class StripeChannelConfiguredConditionTest {

    private final StripeChannelConfiguredCondition condition = new StripeChannelConfiguredCondition();
    private final ConditionContext context = mock(ConditionContext.class);

    private boolean matchesWith(MockEnvironment env) {
        when(context.getEnvironment()).thenReturn(env);
        return condition.matches(context, null);
    }

    @Test
    void blankByDefault_conditionDoesNotMatch() {
        assertFalse(matchesWith(new MockEnvironment()));
    }

    @Test
    void missingWebhookSecret_doesNotMatch() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("marketplace.payments.psp.stripe.api-key", "sk_test_x");
        assertFalse(matchesWith(env));
    }

    @Test
    void missingApiKey_doesNotMatch() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("marketplace.payments.psp.stripe.webhook-secret", "whsec_x");
        assertFalse(matchesWith(env));
    }

    @Test
    void allCredentialsPresent_matches() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("marketplace.payments.psp.stripe.api-key", "sk_test_x");
        env.setProperty("marketplace.payments.psp.stripe.webhook-secret", "whsec_x");
        assertTrue(matchesWith(env));
    }
}
