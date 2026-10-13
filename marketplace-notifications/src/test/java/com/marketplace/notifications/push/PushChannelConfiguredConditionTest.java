package com.marketplace.notifications.push;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 7 (ADR-0003) — the conditional pair's unit gate: the FCM channel
 * exists ONLY while the service-account JSON sits in the environment (the
 * keys-outside-the-code rule), and its absence is the documented inert
 * default (the NoopPushAdapter answers, nothing else breaks).
 */
class PushChannelConfiguredConditionTest {

    private final PushChannelConfiguredCondition condition = new PushChannelConfiguredCondition();
    private final NoopPushAdapter.NotConfigured negation = new NoopPushAdapter.NotConfigured();

    private ConditionContext context(MockEnvironment environment) {
        return new ConditionContext() {
            @Override
            public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() {
                return null;
            }

            @Override
            public Environment getEnvironment() {
                return environment;
            }

            @Override
            public org.springframework.beans.factory.config.ConfigurableListableBeanFactory getBeanFactory() {
                return null;
            }

            @Override
            public org.springframework.core.io.ResourceLoader getResourceLoader() {
                return null;
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

    @Test
    void theAbsentKeyRegistersNothing() {
        assertThat(condition.matches(context(new MockEnvironment()), null)).isFalse();
        assertThat(negation.matches(context(new MockEnvironment()), null)).isTrue();
    }

    @Test
    void theBlankKeyRegistersNothing() {
        MockEnvironment blank = new MockEnvironment();
        blank.setProperty(PushChannelConfiguredCondition.REQUIRED_KEY, "   ");
        assertThat(condition.matches(context(blank), null)).isFalse();
        assertThat(negation.matches(context(blank), null)).isTrue();
    }

    @Test
    void theBoundServiceAccountRegistersTheChannel() {
        MockEnvironment bound = new MockEnvironment();
        bound.setProperty(PushChannelConfiguredCondition.REQUIRED_KEY, "{\"type\":\"service_account\"}");
        assertThat(condition.matches(context(bound), null)).isTrue();
        assertThat(negation.matches(context(bound), null)).isFalse();
    }
}
