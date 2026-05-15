package com.marketplace.app.websocket;

import com.marketplace.messaging.Message;
import com.marketplace.messaging.MessageMapper;
import com.marketplace.messaging.MessagingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.instancio.Instancio;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import static org.instancio.Select.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingWebSocketControllerTest {

    @Mock
    private MessagingService messagingService;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private Principal principal;

    @InjectMocks
    private MessagingWebSocketController controller;

    @Test
    void shouldSendMessage() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID senderId = Instancio.create(UUID.class);
        when(principal.getName()).thenReturn(senderId.toString());
        Message msg = Instancio.of(Message.class)
                .set(field(Message::getConversationId), conversationId)
                .set(field(Message::getSenderId), senderId)
                .set(field(Message::getContent), "hello")
                .create();
        when(messagingService.sendMessage(eq(conversationId), eq(senderId), eq("hello")))
                .thenReturn(msg);

        when(messageMapper.toResponse(msg)).thenReturn(
                new com.marketplace.messaging.MessageResponse(msg.getId(), msg.getConversationId(), msg.getContent(), msg.isRead(), null, null));

        var response = controller.sendMessage(conversationId, Map.of("content", "hello"), principal);

        assertThat(response.id()).isEqualTo(msg.getId());
        assertThat(response.content()).isEqualTo("hello");
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
