package com.marketplace.messaging;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
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
    private final UserLookupPort userLookupPort = mock(UserLookupPort.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MessagingService service = new MessagingService(conversationRepository, messageRepository, bookingParticipantProvider, userLookupPort, messagingTemplate, messageMapper, eventPublisher);

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

    // ------------------------------------------------------------------
    // L44 (neighborhood community plan §5 — direct neighbor messages)
    // ------------------------------------------------------------------

    @Test
    void openDirectConversation_savesCanonicalPairBookingless() {
        // Canonical ordering is the UUID.compareTo contract itself: whoever
        // asks, participant_a = the compareTo-min, participant_b = the
        // compareTo-max — the reversed-direction caller stores the
        // byte-identical pair (the plan's reversed-order criterion). The
        // test DERIVES the expected order with the same rule instead of
        // hand-picking literals (compareTo is signed on the long halves —
        // see the signed-edge test below).
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID first = a.compareTo(b) < 0 ? a : b;
        UUID second = first.equals(a) ? b : a;
        when(userLookupPort.findById(first)).thenReturn(Optional.of(userSummary(first)));
        when(conversationRepository
                .findFirstByBookingIdIsNullAndParticipantAAndParticipantB(first, second))
                .thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        // the canonical-MAX user opens toward the canonical-MIN — the
        // stored pair is still (min, max).
        var outcome = service.openDirectConversation(second, first);

        assertTrue(outcome.newlyCreated());
        assertEquals(first, outcome.conversation().getParticipantA());
        assertEquals(second, outcome.conversation().getParticipantB());
        assertNull(outcome.conversation().getBookingId());
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void openDirectConversation_canonicalOrderIsTheCompareToContractEvenAtTheSignedEdge() {
        // DOCUMENTED EDGE (measured while writing this test): ffffffff-…
        // has the top bit of its most-significant long set, so under
        // UUID.compareTo's SIGNED halves it is LESS than 00000000-… — the
        // opposite of byte-wise unsigned comparison. That is fine by
        // design: the canonical order is the compareTo contract (the
        // plan's own words — a total order, so the pair maps to ONE
        // storage shape regardless of direction), while the V67 DB guard
        // is LEAST/GREATEST on the byte representation. The two orders
        // are INDEPENDENT guards — the application finder and the
        // uniqueness backstop — and never need to agree with each other.
        UUID allOnes = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID allZeros = UUID.fromString("00000000-0000-0000-0000-000000000001");
        org.junit.jupiter.api.Assertions.assertTrue(allOnes.compareTo(allZeros) < 0,
                "the signed-halves contract: ffffffff-… < 00000000-…");
        // Either direction's recipient must resolve through the seam.
        when(userLookupPort.findById(allOnes)).thenReturn(Optional.of(userSummary(allOnes)));
        when(userLookupPort.findById(allZeros)).thenReturn(Optional.of(userSummary(allZeros)));
        when(conversationRepository
                .findFirstByBookingIdIsNullAndParticipantAAndParticipantB(allOnes, allZeros))
                .thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        // allZeros opens toward allOnes — compareTo-min is allOnes, so the
        // stored pair is (allOnes, allZeros), byte-stable from either side.
        var outcome = service.openDirectConversation(allZeros, allOnes);

        assertEquals(allOnes, outcome.conversation().getParticipantA());
        assertEquals(allZeros, outcome.conversation().getParticipantB());
        // and the reversed direction finds it — same finder arguments.
        when(conversationRepository
                .findFirstByBookingIdIsNullAndParticipantAAndParticipantB(allOnes, allZeros))
                .thenReturn(Optional.of(outcome.conversation()));
        var reversed = service.openDirectConversation(allOnes, allZeros);
        assertFalse(reversed.newlyCreated());
    }

    @Test
    void openDirectConversation_reusesExistingForSamePairEitherSide() {
        // The idempotent reuse (the findByBookingId precedent): the second
        // open — from EITHER side — returns the existing conversation with
        // newlyCreated=false (the controller's 200), and no second row is
        // written. Expected canonical order derived by the same compareTo
        // rule the service applies.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID first = a.compareTo(b) < 0 ? a : b;
        UUID second = first.equals(a) ? b : a;
        Conversation existing = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), first)
                .set(field(Conversation::getParticipantB), second)
                .set(field(Conversation::getBookingId), null)
                .create();
        when(userLookupPort.findById(any(UUID.class))).thenAnswer(inv -> Optional.of(userSummary(inv.getArgument(0))));
        when(conversationRepository
                .findFirstByBookingIdIsNullAndParticipantAAndParticipantB(first, second))
                .thenReturn(Optional.of(existing));

        var fromFirst = service.openDirectConversation(first, second);
        var fromSecond = service.openDirectConversation(second, first);

        assertFalse(fromFirst.newlyCreated());
        assertFalse(fromSecond.newlyCreated());
        assertEquals(existing.getId(), fromFirst.conversation().getId());
        assertEquals(existing.getId(), fromSecond.conversation().getId());
        verify(conversationRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void openDirectConversation_rejectsSelfBeforeAnyLookup() {
        UUID self = Instancio.create(UUID.class);

        assertThrows(BadRequestException.class, () -> service.openDirectConversation(self, self));
        verifyNoInteractions(userLookupPort, conversationRepository);
    }

    @Test
    void openDirectConversation_rejectsUnknownRecipientWith404() {
        UUID requester = Instancio.create(UUID.class);
        UUID stranger = Instancio.create(UUID.class);
        when(userLookupPort.findById(stranger)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.openDirectConversation(requester, stranger));
        verify(conversationRepository, never()).save(any());
    }

    private static UserSummary userSummary(UUID userId) {
        return new UserSummary(userId, "neighbor@example.com", "A Neighbor", "CONSUMER", null, null);
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
    void getConversation_returnsForParticipant() {
        UUID id = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), userId)
                .set(field(Conversation::getBookingId), null)
                .create();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));

        Conversation result = service.getConversation(id, userId);

        assertNotNull(result);
    }

    @Test
    void getConversation_rejectsNonParticipant() {
        UUID id = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), Instancio.create(UUID.class))
                .set(field(Conversation::getParticipantB), Instancio.create(UUID.class))
                .set(field(Conversation::getBookingId), null)
                .create();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));

        assertThrows(AccessDeniedException.class, () -> service.getConversation(id, userId));
    }

    @Test
    void getMessages_returnsPage() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), userId)
                .set(field(Conversation::getBookingId), null)
                .create();
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        Message msg = Message.create(conversationId, userId, "Hi");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(msg)));

        var result = service.getMessages(conversationId, userId, pageable);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getUnreadCount_returnsCount() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), userId)
                .set(field(Conversation::getBookingId), null)
                .create();

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationIdAndReadFalse(conversationId)).thenReturn(5L);

        long count = service.getUnreadCount(conversationId, userId);

        assertEquals(5L, count);
    }

    @Test
    void markAsRead_delegatesToRepository() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), userId)
                .set(field(Conversation::getBookingId), null)
                .create();

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));

        service.markAsRead(conversationId, userId);

        verify(messageRepository).markAsReadByConversationId(conversationId, userId);
    }
}
