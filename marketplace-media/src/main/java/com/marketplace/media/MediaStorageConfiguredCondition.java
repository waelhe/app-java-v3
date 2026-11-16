package com.marketplace.media;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the S3 storage beans only when ALL storage credentials are present
 * and non-blank. This is the framework's own conditional SPI — the multi-key
 * equivalent of {@code @ConditionalOnProperty} (which is not repeatable and
 * cannot express "every key non-blank").
 *
 * <p>The blank/absent state is the documented inert default: media endpoints
 * then answer 503 instead of half-working, exactly like the MAIL provider gate.
 */
class MediaStorageConfiguredCondition implements Condition {

    private static final String[] REQUIRED_KEYS = {
            "marketplace.media.storage.endpoint",
            "marketplace.media.storage.bucket",
            "marketplace.media.storage.access-key",
            "marketplace.media.storage.secret-key"
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
