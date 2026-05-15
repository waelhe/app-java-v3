package com.marketplace.messaging;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final MessagingService service = new MessagingService(conversationRepository, messageRepository, bookingParticipantProvider, messagingTemplate, messageMapper);

    @Test
    void createConversation_savesNewUsingBookingParticipants() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        UUID bookingId = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), participantA)
                .set(field(BookingInfo::consumerId), participantB)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

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
        UUID bookingId = Instancio.create(UUID.class);
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), participantA)
                .set(field(BookingInfo::consumerId), participantB)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        Conversation existing = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), bookingId)
                .create();

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        Conversation result = service.createConversation(existing.getParticipantA(), bookingId);

        assertEquals(existing.getId(), result.getId());
        verify(conversationRepository, never()).save(any());
    }


    @Test
    void createConversation_rejectsWhenNotParticipant() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), participantA)
                .set(field(BookingInfo::consumerId), participantB)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        UUID outsider = Instancio.create(UUID.class);

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(AccessDeniedException.class,
                () -> service.createConversation(outsider, bookingId));
    }

    @Test
    void createConversation_rejectsWhenBookingCancelled() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), participantA)
                .set(field(BookingInfo::consumerId), participantB)
                .set(field(BookingInfo::status), "CANCELLED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(IllegalStateException.class, () -> service.createConversation(participantA, bookingId));
    }

    @Test
    void sendMessage_validParticipant() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message msg = service.sendMessage(conv.getId(), participantA, "Hello!");

        assertEquals("Hello!", msg.getContent());
        assertEquals(participantA, msg.getSenderId());
    }

    @Test
    void sendMessage_rejectsNonParticipant() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();
        UUID outsider = Instancio.create(UUID.class);

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));

        assertThrows(AccessDeniedException.class,
                () -> service.sendMessage(conv.getId(), outsider, "hack"));
    }

    @Test
    void getConversation_throwsWhenNotFound() {
        UUID id = Instancio.create(UUID.class);
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getConversation(id, Instancio.create(UUID.class)));
    }
}
