package com.marketplace.media;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaStorageConfiguredConditionTest {

    private final MediaStorageConfiguredCondition condition = new MediaStorageConfiguredCondition();
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
    void anyMissingCredential_doesNotMatch() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("marketplace.media.storage.endpoint", "https://r2.example");
        env.setProperty("marketplace.media.storage.bucket", "media");
        env.setProperty("marketplace.media.storage.access-key", "key");
        // secret-key missing
        assertFalse(matchesWith(env));
    }

    @Test
    void allCredentialsPresent_matches() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("marketplace.media.storage.endpoint", "https://r2.example");
        env.setProperty("marketplace.media.storage.bucket", "media");
        env.setProperty("marketplace.media.storage.access-key", "key");
        env.setProperty("marketplace.media.storage.secret-key", "secret");
        assertTrue(matchesWith(env));
    }
}
