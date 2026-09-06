package com.marketplace.payments;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the Stripe channel bean only when BOTH credentials are present
 * and non-blank. This is the framework's own conditional SPI — the
 * multi-key equivalent of {@code @ConditionalOnProperty} (which cannot
 * express "every key non-blank"), the same shape as the media module's
 * {@code MediaStorageConfiguredCondition}.
 *
 * <p>The blank/absent state is the documented inert default: the internal
 * intent flow keeps working exactly as before, and the Stripe webhook
 * endpoint answers 503 SU-001 instead of half-verifying.
 */
class StripeChannelConfiguredCondition implements Condition {

    private static final String[] REQUIRED_KEYS = {
            "marketplace.payments.psp.stripe.api-key",
            "marketplace.payments.psp.stripe.webhook-secret"
    };

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = env.getProperty(key);
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
