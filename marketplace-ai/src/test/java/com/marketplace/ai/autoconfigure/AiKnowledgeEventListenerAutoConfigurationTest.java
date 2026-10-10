package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiKnowledgeEntryEventListener;
import com.marketplace.ai.AiKnowledgeGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiKnowledgeEventListenerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiKnowledgeEventListenerAutoConfiguration.class));

    @Test
    void doesNotRegisterListenerWhenRagGatewayIsUnavailable() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(AiKnowledgeEntryEventListener.class));
    }

    @Test
    void registersListenerWhenRagGatewayIsAvailable() {
        contextRunner
                .withBean(AiKnowledgeGateway.class, () -> mock(AiKnowledgeGateway.class))
                .run(context -> assertThat(context).hasSingleBean(AiKnowledgeEntryEventListener.class));
    }
}
