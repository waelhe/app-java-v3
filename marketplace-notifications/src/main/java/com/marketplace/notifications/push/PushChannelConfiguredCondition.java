package com.marketplace.notifications.push;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Stage 7 (ADR-0003): registers the FCM push channel bean only when the
 * service-account JSON is present and non-blank — the framework's own
 * conditional SPI (the {@code StripeChannelConfiguredCondition} shape
 * verbatim). The absent state is the documented inert default: the
 * {@code NoopPushAdapter} answers, the token registry and the preference
 * semantics keep working, and no other notification flow breaks.
 */
class PushChannelConfiguredCondition implements Condition {

    static final String REQUIRED_KEY = "marketplace.push.fcm.credentials-json";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(REQUIRED_KEY);
        return value != null && !value.isBlank();
    }
}
