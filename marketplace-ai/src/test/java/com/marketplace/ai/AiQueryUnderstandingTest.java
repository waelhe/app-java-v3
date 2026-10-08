package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiQueryUnderstandingTest {
    @Test
    void mapsStructuredResponseThroughSpringAiEntityApi() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        AiSearchIntent intent = new AiSearchIntent(
                AiSearchIntent.Intent.SEARCH, "laptop", "electronics", null, null, null);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(request);
        when(request.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.entity(
                org.mockito.ArgumentMatchers.eq(AiSearchIntent.class),
                org.mockito.ArgumentMatchers.<Consumer<ChatClient.EntityParamSpec>>any()
        )).thenReturn(intent);
        assertThat(new AiQueryUnderstanding(builder).understand("laptop")).isSameAs(intent);
    }
}
