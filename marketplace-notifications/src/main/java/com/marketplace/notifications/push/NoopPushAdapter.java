package com.marketplace.notifications.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage 7 (ADR-0003): the fail-safe fallback — when the FCM credentials
 * are absent from the environment, the channel is an HONEST no-op: every
 * send reports failure-without-pruning (the registry keeps its tokens),
 * the preference semantics stay intact, and nothing else in the
 * notification flow breaks (the plan's fail-safe rule: «تعطيل مزود لا
 * يجعل الوظائف الحتمية ترجع نتائج مختلقة أو تتعطل بلا داعٍ»).
 */
@Component
@org.springframework.context.annotation.Conditional(NoopPushAdapter.NotConfigured.class)
class NoopPushAdapter implements PushNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(NoopPushAdapter.class);

    @Override
    public List<PushResult> send(List<String> tokens, String title, String body) {
        log.debug("Push channel inert (no FCM credentials bound) — {} token(s) reported unattempted",
                tokens.size());
        return tokens.stream().map(t -> new PushResult(t, false, false)).toList();
    }

    /** The negating half of the conditional pair (the Stripe condition's twin). */
    static final class NotConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !new PushChannelConfiguredCondition().matches(context, metadata);
        }
    }
}
