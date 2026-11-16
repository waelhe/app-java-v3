package com.marketplace.app.websocket;

import com.marketplace.messaging.MessageResponse;
import com.marketplace.messaging.MessagingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

import org.instancio.Instancio;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingWebSocketControllerTest {

    @Mock
    private MessagingService messagingService;

    @Mock
    private Principal principal;

    @InjectMocks
    private MessagingWebSocketController controller;

    /**
     * POST over WebSocket returns the mapped message payload — B1: the
     * response now carries the authenticated sender's id as
     * {@code senderId} (canonical constructor gained the third component).
     */
    @Test
    void shouldSendMessage() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID senderId = Instancio.create(UUID.class);
        when(principal.getName()).thenReturn(senderId.toString());
        MessageResponse response = new MessageResponse(
                Instancio.create(UUID.class), conversationId, senderId, "hello", false, null, null);
        when(messagingService.sendMessage(eq(conversationId), eq(senderId), eq("hello")))
                .thenReturn(response);

        var result = controller.sendMessage(conversationId, Map.of("content", "hello"), principal);

        assertThat(result.id()).isEqualTo(response.id());
        assertThat(result.senderId()).isEqualTo(senderId);
        assertThat(result.content()).isEqualTo("hello");
    }


    @Test
    void rejectsMissingPrincipal() {
        UUID conversationId = Instancio.create(UUID.class);

        assertThatThrownBy(() -> controller.sendMessage(conversationId, Map.of("content", "hello"), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authenticated WebSocket principal");
    }

    @Test
    void rejectsUnauthenticatedPrincipal() {
        UUID conversationId = Instancio.create(UUID.class);
        TestingAuthenticationToken unauthenticated = new TestingAuthenticationToken(UUID.randomUUID().toString(), "n/a");
        unauthenticated.setAuthenticated(false);

        assertThatThrownBy(() -> controller.sendMessage(conversationId, Map.of("content", "hello"), unauthenticated))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authenticated WebSocket principal");
    }

    @Test
    void rejectsPrincipalWithoutUuidName() {
        UUID conversationId = Instancio.create(UUID.class);
        when(principal.getName()).thenReturn("not-a-uuid");

        assertThatThrownBy(() -> controller.sendMessage(conversationId, Map.of("content", "hello"), principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("UUID subject");
    }

    @Test
    void shouldMarkRead() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        when(principal.getName()).thenReturn(userId.toString());

        controller.markRead(conversationId, principal);

        verify(messagingService).markAsRead(conversationId, userId);
    }
}
