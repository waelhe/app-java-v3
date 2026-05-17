package com.marketplace.messaging;

import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagingControllerTest {

    @Mock
    private MessagingService messagingService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationMapper conversationMapper;

    @InjectMocks
    private MessagingController controller;

    @Test
    void getConversation_returnsConversation() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        Conversation conv = new Conversation(id, UUID.randomUUID(), userId, UUID.randomUUID());
        ConversationResponse response = new ConversationResponse(id, UUID.randomUUID(), null, null);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(messagingService.getConversation(id, userId)).thenReturn(conv);
        when(conversationMapper.toResponse(conv)).thenReturn(response);

        ResponseEntity<ConversationResponse> result = controller.getConversation(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void getMessages_returnsPagedMessages() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        PageRequest pageable = PageRequest.of(0, 10);
        Message msg = new Message(UUID.randomUUID(), conversationId, userId, "Hi");
        Page<Message> page = new PageImpl<>(List.of(msg));

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(messagingService.getMessages(conversationId, userId, pageable)).thenReturn(page);

        ResponseEntity<PagedResponse<MessageResponse>> result = controller.getMessages(conversationId, pageable, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void getUnreadCount_returnsCount() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(messagingService.getUnreadCount(conversationId, userId)).thenReturn(3L);

        ResponseEntity<MessagingController.UnreadCountResponse> result = controller.getUnreadCount(conversationId, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(3L, result.getBody().unreadCount());
    }

    @Test
    void createConversation_createsAndReturns201() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        var request = new MessagingController.CreateConversationRequest(bookingId);
        Conversation conv = Conversation.create(userId, UUID.randomUUID(), bookingId);
        ConversationResponse response = new ConversationResponse(UUID.randomUUID(), bookingId, null, null);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(messagingService.createConversation(userId, bookingId)).thenReturn(conv);
        when(conversationMapper.toResponse(conv)).thenReturn(response);

        ResponseEntity<ConversationResponse> result = controller.createConversation(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void sendMessage_createsAndReturns201() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        var request = new MessagingController.SendMessageRequest("Hello");
        Message msg = Message.create(conversationId, userId, "Hello");
        MessageResponse response = new MessageResponse(UUID.randomUUID(), conversationId, "Hello", false, null, null);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(messagingService.sendMessage(conversationId, userId, "Hello")).thenReturn(msg);
        when(messageMapper.toResponse(msg)).thenReturn(response);

        ResponseEntity<MessageResponse> result = controller.sendMessage(conversationId, request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void markAsRead_returnsOk() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);

        ResponseEntity<Void> result = controller.markAsRead(conversationId, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(messagingService).markAsRead(conversationId, userId);
    }
}
