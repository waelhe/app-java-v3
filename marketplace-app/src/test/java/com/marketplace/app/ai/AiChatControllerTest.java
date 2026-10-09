package com.marketplace.app.ai;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatControllerTest {

    private final ObjectProvider<AiChatGateway> gatewayProvider = mock(ObjectProvider.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final AiChatController controller =
            new AiChatController(gatewayProvider, currentUserProvider);

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void continuesTheRequestedConversationAndReturnsTheAssistantAnswer() {
        UUID conversationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        AiChatGateway gateway = mock(AiChatGateway.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(gateway.answer(userId, conversationId.toString(), "Where is the nearest park?"))
                .thenReturn("There is a park nearby.");

        ResponseEntity<AiChatResponse> response = controller.chat(
                new AiChatRequest("Where is the nearest park?", conversationId), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().conversationId()).isEqualTo(conversationId);
        assertThat(response.getBody().answer()).isEqualTo("There is a park nearby.");
        verify(gateway).answer(userId, conversationId.toString(), "Where is the nearest park?");
    }

    @Test
    void createsAConversationIdWhenTheClientOmitsIt() {
        AiChatGateway gateway = mock(AiChatGateway.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(gateway.answer(eq(userId), anyString(), eq("Hello"))).thenReturn("Hello.");

        ResponseEntity<AiChatResponse> response =
                controller.chat(new AiChatRequest("Hello", null), authentication);

        assertThat(response.getBody()).isNotNull();
        UUID returnedId = response.getBody().conversationId();
        assertThat(returnedId).isNotNull();
        verify(gateway).answer(userId, returnedId.toString(), "Hello");
    }

    @Test
    void returnsAServiceUnavailableProblemWhenChatIsDisabled() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> controller.chat(new AiChatRequest("Hello", null), authentication))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("AI chat is not available in the current configuration.");
    }
}
