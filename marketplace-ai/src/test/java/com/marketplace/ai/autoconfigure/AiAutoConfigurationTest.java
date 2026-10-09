package com.marketplace.ai.autoconfigure;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.ai.AiQueryUnderstanding;
import com.marketplace.ai.MarketplaceSearchTools;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.ai.JevModelRouter;
import org.springaicommunity.typesafe.advisor.JevGuardrailAdvisor;
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
                        .hasSingleBean(AiQueryUnderstanding.class));
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
                        .hasSingleBean(AiQueryUnderstanding.class));
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

        AiChatGateway gateway = new AiAutoConfiguration()
                .aiChatGateway(builder, sessionService, searchTools, modelRouters, guardrailAdvisors, retrievalAugmentationAdvisors, vectorStores);

        assertThat(gateway).isNotNull();
        verify(searchTools).ifAvailable(any());
        verify(vectorStores).ifAvailable(any());
    }

    @Test
    void registersMarketplaceSearchToolsOnlyWhenCatalogSearchPortIsAvailable() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(MarketplaceSearchTools.class));

        contextRunner
                .withBean(CatalogSearchPort.class, () -> mock(CatalogSearchPort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(MarketplaceSearchTools.class));
    }

    @Test
    void createsQueryUnderstandingFromSpringAiManagedBuilder() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        AiQueryUnderstanding understanding =
                new AiAutoConfiguration().aiQueryUnderstanding(builder);

        assertThat(understanding).isNotNull();
    }
}
