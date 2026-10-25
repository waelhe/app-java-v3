package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.AiKnowledgeGateway;
import com.marketplace.ai.AiQueryUnderstanding;
import com.marketplace.ai.AiWithdrawnSourceStore;
import com.marketplace.ai.MarketplaceSearchTools;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.ai.JevModelRouter;
import org.springaicommunity.typesafe.advisor.JevGuardrailAdvisor;
import org.springaicommunity.typesafe.advisor.JevSelfRefineAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ChatClientAutoConfiguration.class,
                    AiAutoConfiguration.class));

    @Test
    void backsOffWhenNoChatModelIsAvailable() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=none",
                        "spring.ai.chat.client.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(AiChatGateway.class)
                        .doesNotHaveBean(AiQueryUnderstanding.class));
    }

    @Test
    void activatesWhenSpringAiProvidesChatModel() {
        contextRunner
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(AiChatGateway.class)
                        // Task 5-f: the orphan AiQueryUnderstanding bean is retired —
                        // it had no consumer anywhere, so the auto-configuration no
                        // longer registers it even on the fully-activated path.
                        .doesNotHaveBean(AiQueryUnderstanding.class));
    }

    @Test
    void usesSpringAiManagedBuilderWithoutRequiringAChatModelBean() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        contextRunner
                .withBean(ChatClient.Builder.class, () -> builder)
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(AiChatGateway.class)
                        .doesNotHaveBean(AiQueryUnderstanding.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void composesOptionalOfficialExtensionsWhenPresent() {
        ChatModel chatModel = mock(ChatModel.class);
        SessionService sessionService = mock(SessionService.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        MarketplaceSearchTools searchTool = mock(MarketplaceSearchTools.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<MarketplaceSearchTools> searchTools = mock(ObjectProvider.class);
        ObjectProvider<VectorStore> vectorStores = mock(ObjectProvider.class);
        ObjectProvider<JevModelRouter> modelRouters = mock(ObjectProvider.class);
        ObjectProvider<JevSelfRefineAdvisor> selfRefineAdvisors = mock(ObjectProvider.class);
        ObjectProvider<JevGuardrailAdvisor> guardrailAdvisors = mock(ObjectProvider.class);
        ObjectProvider<RetrievalAugmentationAdvisor> retrievalAugmentationAdvisors =
                mock(ObjectProvider.class);
        when(retrievalAugmentationAdvisors.orderedStream()).thenReturn(Stream.empty());

        doAnswer(invocation -> {
            ((Consumer<MarketplaceSearchTools>) invocation.getArgument(0)).accept(searchTool);
            return null;
        }).when(searchTools).ifAvailable(any());

        doAnswer(invocation -> {
            ((Consumer<VectorStore>) invocation.getArgument(0)).accept(vectorStore);
            return null;
        }).when(vectorStores).ifAvailable(any());

        ObjectProvider<AiWithdrawnSourceStore> withdrawnSources = mock(ObjectProvider.class);
        when(withdrawnSources.getIfAvailable()).thenReturn(mock(AiWithdrawnSourceStore.class));

        AiChatGateway gateway = new AiAutoConfiguration()
                .aiChatGateway(builder, sessionService, searchTools, modelRouters, selfRefineAdvisors, guardrailAdvisors, retrievalAugmentationAdvisors, vectorStores, withdrawnSources);

        assertThat(gateway).isNotNull();
        verify(searchTools).ifAvailable(any());
        verify(vectorStores).ifAvailable(any());
    }

    @Test
    void registersMarketplaceSearchToolsOnlyWhenCanonicalPortsAreAvailable() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=none",
                        "spring.ai.chat.client.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MarketplaceSearchTools.class));

        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=none",
                        "spring.ai.chat.client.enabled=false")
                .withBean(MarketplaceSearchPort.class, () -> mock(MarketplaceSearchPort.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MarketplaceSearchTools.class));

        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=none",
                        "spring.ai.chat.client.enabled=false")
                .withBean(MarketplaceSearchPort.class, () -> mock(MarketplaceSearchPort.class))
                .withBean(GeoLookupPort.class, () -> mock(GeoLookupPort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(MarketplaceSearchTools.class));
    }

    /*
     * Task 5-f: the former createsQueryUnderstandingFromSpringAiManagedBuilder
     * pin is retired with the bean itself — it invoked the removed
     * aiQueryUnderstanding(...) bean method, and an auto-configuration pin of
     * an orphan bean would assert the exact dead weight this wave removes.
     * The class's own mapping behavior stays pinned by AiQueryUnderstandingTest.
     */

    /**
     * Row 32 (agent findings 2026-10-25, CWE-367): the withdrawal record's
     * JDBC infrastructure AND a transaction manager — the per-source
     * publication/withdrawal advisory-lock protocol is transaction-scoped
     * (held to the commit that makes the record and the vector writes
     * visible together). Without a transaction manager there is no
     * protocol, so the gateway backs off rather than index without
     * serialization; the middle block below is exactly that back-off.
     */
    @Test
    void knowledgeGatewayNeedsTheExactRecordStoreSoNeverDependsOnVectorRecall() {
        // VectorStore alone is no longer enough: the withdrawal decision is
        // the exact keyed record, so the gateway composes only when the JDBC
        // infrastructure for that record is present.
        contextRunner
                .withPropertyValues("spring.ai.model.chat=none", "spring.ai.chat.client.enabled=false")
                .withBean(VectorStore.class, () -> mock(VectorStore.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(AiWithdrawnSourceStore.class)
                        .doesNotHaveBean(AiKnowledgeGateway.class));

        // ...and row 32: still not enough without a transaction manager —
        // the advisory lock would be released at statement end, so the
        // gateway refuses to exist rather than degrade the protocol.
        contextRunner
                .withPropertyValues("spring.ai.model.chat=none", "spring.ai.chat.client.enabled=false")
                .withBean(VectorStore.class, () -> mock(VectorStore.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(AiWithdrawnSourceStore.class)
                        .doesNotHaveBean(AiKnowledgeGateway.class));

        contextRunner
                .withPropertyValues("spring.ai.model.chat=none", "spring.ai.chat.client.enabled=false")
                .withBean(VectorStore.class, () -> mock(VectorStore.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(AiWithdrawnSourceStore.class)
                        .hasSingleBean(AiKnowledgeGateway.class));
    }
}
