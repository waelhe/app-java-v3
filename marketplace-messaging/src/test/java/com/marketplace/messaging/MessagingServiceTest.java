package com.marketplace.messaging;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final MessagingService service = new MessagingService(
            conversationRepository, messageRepository, bookingParticipantProvider);

    @Test
    void createConversation_savesNew() {
        UUID participantA = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID participantB = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(
                participantB, participantA, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation conv = service.createConversation(participantA, bookingId);

        assertEquals(participantA, conv.getParticipantA());
        assertEquals(participantB, conv.getParticipantB());
        assertEquals(bookingId, conv.getBookingId());
    }

    @Test
    void createConversation_reusesExistingForSameBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID participantA = UUID.randomUUID();
        UUID participantB = UUID.randomUUID();
        Conversation existing = Conversation.create(participantA, participantB, bookingId);
        BookingInfo bookingInfo = new BookingInfo(
                participantB, participantA, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        Conversation result = service.createConversation(participantA, bookingId);

        assertEquals(existing.getId(), result.getId());
        verify(conversationRepository, never()).save(any());
    }


    @Test
    void createConversation_rejectsExistingWhenNotParticipant() {
        UUID bookingId = UUID.randomUUID();
        UUID participantA = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID participantB = UUID.randomUUID();
        Conversation existing = Conversation.create(participantA, participantB, bookingId);
        BookingInfo bookingInfo = new BookingInfo(
                participantB, participantA, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        assertThrows(AccessDeniedException.class,
                () -> service.createConversation(outsider, bookingId));
    }

    @Test
    void createConversation_rejectsCancelledBooking() {
        UUID participantA = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(
                UUID.randomUUID(), participantA, "CANCELLED", 5000L, "SAR", Instant.now(), Instant.now());

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(IllegalStateException.class, () -> service.createConversation(participantA, bookingId));
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

        assertThrows(AccessDeniedException.class,
                () -> service.sendMessage(conv.getId(), outsider, "hack"));
    }

    @Test
    void getConversation_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getConversation(id, UUID.randomUUID()));
    }
}
