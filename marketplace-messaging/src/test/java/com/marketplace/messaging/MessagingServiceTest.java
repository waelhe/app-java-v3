package com.marketplace.messaging;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
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

    @Test
    void getConversation_returnsConversation() {
        UUID id = Instancio.create(UUID.class);
        UUID participantA = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .create();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));
        Conversation result = service.getConversation(id, participantA);
        assertEquals(conv.getId(), result.getId());
    }

    @Test
    void getConversation_throwsWhenNotParticipant() {
        UUID id = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), Instancio.create(UUID.class))
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .create();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));
        assertThrows(AccessDeniedException.class,
                () -> service.getConversation(id, Instancio.create(UUID.class)));
    }

    @Test
    void getMessages_returnsMessagesForParticipant() {
        UUID participantA = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .create();
        var pageable = PageRequest.of(0, 10);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(conv.getId(), pageable))
                .thenReturn(Page.empty());
        assertNotNull(service.getMessages(conv.getId(), participantA, pageable));
    }

    @Test
    void getUnreadCount_returnsCount() {
        UUID participantA = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .create();
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationIdAndReadFalse(conv.getId())).thenReturn(3L);
        assertEquals(3, service.getUnreadCount(conv.getId(), participantA));
    }

    @Test
    void markAsRead_marksMessages() {
        UUID participantA = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .create();
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        service.markAsRead(conv.getId(), participantA);
        verify(messageRepository).markAsReadByConversationId(conv.getId(), participantA);
    }

    @Test
    void sendMessage_publishesToWebSocket() {
        UUID participantA = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .create();
        MessageResponse response = new MessageResponse(Instancio.create(UUID.class), Instancio.create(UUID.class), "Hello!", false);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageMapper.toResponse(any(Message.class))).thenReturn(response);
        service.sendMessage(conv.getId(), participantA, "Hello!");
        verify(messagingTemplate).convertAndSend(eq("/topic/conversations/" + conv.getId()), eq(response));
    }

    @Test
    void createConversation_whenProviderIsParticipantA_createsConversation() {
        UUID providerId = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        UUID bookingId = Instancio.create(UUID.class);
        BookingInfo bookingInfo = Instancio.of(BookingInfo.class)
                .set(field(BookingInfo::providerId), providerId)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(conversationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        Conversation conv = service.createConversation(providerId, bookingId);
        assertEquals(providerId, conv.getParticipantA());
        assertEquals(consumerId, conv.getParticipantB());
    }

    @Test
    void message_create_setsFields() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID senderId = Instancio.create(UUID.class);
        Message msg = Message.create(conversationId, senderId, "test content");
        assertEquals(conversationId, msg.getConversationId());
        assertEquals(senderId, msg.getSenderId());
        assertEquals("test content", msg.getContent());
        assertFalse(msg.isRead());
        assertNotNull(msg.getId());
    }

    @Test
    void message_markRead_setsReadFlag() {
        Message msg = Message.create(Instancio.create(UUID.class), Instancio.create(UUID.class), "test");
        assertFalse(msg.isRead());
        msg.markRead();
        assertTrue(msg.isRead());
    }

    @Test
    void conversation_hasParticipant_returnsTrueForBoth() {
        UUID a = Instancio.create(UUID.class);
        UUID b = Instancio.create(UUID.class);
        Conversation conv = Conversation.create(a, b, Instancio.create(UUID.class));
        assertTrue(conv.hasParticipant(a));
        assertTrue(conv.hasParticipant(b));
        assertFalse(conv.hasParticipant(Instancio.create(UUID.class)));
    }
}
