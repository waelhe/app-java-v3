package com.marketplace.messaging;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final MessagingService service = new MessagingService(conversationRepository, messageRepository);

    @Test
    void createConversation_savesNew() {
        UUID participantA = UUID.randomUUID();
        UUID participantB = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation conv = service.createConversation(participantA, participantB, bookingId);

        assertEquals(participantA, conv.getParticipantA());
        assertEquals(participantB, conv.getParticipantB());
        assertEquals(bookingId, conv.getBookingId());
    }

    @Test
    void createConversation_reusesExistingForSameBooking() {
        UUID bookingId = UUID.randomUUID();
        Conversation existing = Conversation.create(UUID.randomUUID(), UUID.randomUUID(), bookingId);

        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        Conversation result = service.createConversation(UUID.randomUUID(), UUID.randomUUID(), bookingId);

        assertEquals(existing.getId(), result.getId());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void sendMessage_validParticipant() {
        UUID participantA = UUID.randomUUID();
        UUID participantB = UUID.randomUUID();
        Conversation conv = Conversation.create(participantA, participantB, null);

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message msg = service.sendMessage(conv.getId(), participantA, "Hello!");

        assertEquals("Hello!", msg.getContent());
        assertEquals(participantA, msg.getSenderId());
    }

    @Test
    void sendMessage_rejectsNonParticipant() {
        UUID participantA = UUID.randomUUID();
        UUID participantB = UUID.randomUUID();
        Conversation conv = Conversation.create(participantA, participantB, null);
        UUID outsider = UUID.randomUUID();

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));

        assertThrows(IllegalArgumentException.class,
                () -> service.sendMessage(conv.getId(), outsider, "hack"));
    }

    @Test
    void getConversation_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getConversation(id));
    }
}