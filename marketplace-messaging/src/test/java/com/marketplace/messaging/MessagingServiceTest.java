package com.marketplace.messaging;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.MessageReceivedEvent;
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
        return new UserSummary(userId, "neighbor@example.com", "A Neighbor", "CONSUMER",
                null, null, null);
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
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageMapper.toResponse(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            return new MessageResponse(saved.getId(), saved.getConversationId(), saved.getSenderId(),
                    saved.getContent(), saved.isRead(), saved.getCreatedAt(), saved.getUpdatedAt());
        });

        MessageResponse msg = service.sendMessage(conv.getId(), participantA, "Hello!");

        assertEquals("Hello!", msg.content());
        assertEquals(participantA, msg.senderId());
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

    /**
     * B-04 (compliance plan 0.4 — Data JPA jpa/locking.html): the first
     * keyed submission persists the replay surface WITH the message and
     * broadcasts once; the sequential replay (below) and the in-flight
     * race (V150's UNIQUE index + @Version on every row) close the
     * duplication story end to end.
     */
    @Test
    void sendMessage_withKey_persistsTheReplaySurfaceAndBroadcastsOnce() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.findBySenderIdAndIdempotencyKey(participantA, "msg-2026-10-07-001"))
                .thenReturn(Optional.empty());
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageMapper.toResponse(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            return new MessageResponse(saved.getId(), saved.getConversationId(), saved.getSenderId(),
                    saved.getContent(), saved.isRead(), saved.getCreatedAt(), saved.getUpdatedAt());
        });

        MessagingService.SendMessageOutcome outcome =
                service.sendMessage(conv.getId(), participantA, "Hello!", "msg-2026-10-07-001");

        assertTrue(outcome.newlyCreated());
        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("msg-2026-10-07-001", captor.getValue().getIdempotencyKey());
        assertEquals(participantA, captor.getValue().getSenderId());
        verify(messagingTemplate).convertAndSend(eq("/topic/conversations/" + conv.getId()), any(Object.class));
    }

    /**
     * B-04 (0.4): the sequential retry — same key, same sender — returns
     * the ORIGINAL message (same id), saves nothing, and never re-broadcasts
     * (the topic already carries the original push; a second broadcast
     * would duplicate it for every other subscriber).
     */
    @Test
    void sendMessage_replayedKey_returnsTheOriginalWithoutDuplicateOrRebroadcast() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();
        Message original = Message.create(conv.getId(), participantA, "Hello!", "msg-2026-10-07-001");

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.findBySenderIdAndIdempotencyKey(participantA, "msg-2026-10-07-001"))
                .thenReturn(Optional.of(original));
        when(messageMapper.toResponse(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            return new MessageResponse(saved.getId(), saved.getConversationId(), saved.getSenderId(),
                    saved.getContent(), saved.isRead(), saved.getCreatedAt(), saved.getUpdatedAt());
        });

        MessagingService.SendMessageOutcome outcome =
                service.sendMessage(conv.getId(), participantA, "Hello! (client retried)", "msg-2026-10-07-001");

        assertFalse(outcome.newlyCreated());
        assertEquals(original.getId(), outcome.message().id());
        verify(messageRepository, never()).saveAndFlush(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        // B-08: the replay never re-publishes the arrival fact either —
        // the original send already notified the recipient.
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * B-08 (compliance plan 0.10 — the unit's publication gate): every
     * real send publishes the arrival fact with the recipient resolved at
     * the source — the OTHER participant, never the sender. The replay
     * (above) never re-publishes.
     */
    @Test
    void sendMessage_publishesMessageReceivedEventForTheOtherParticipant() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageMapper.toResponse(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            return new MessageResponse(saved.getId(), saved.getConversationId(), saved.getSenderId(),
                    saved.getContent(), saved.isRead(), saved.getCreatedAt(), saved.getUpdatedAt());
        });

        service.sendMessage(conv.getId(), participantA, "Hello!");

        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertTrue(captor.getValue() instanceof MessageReceivedEvent);
        MessageReceivedEvent event = (MessageReceivedEvent) captor.getValue();
        assertEquals(conv.getId(), event.conversationId());
        assertEquals(participantA, event.senderId());
        assertEquals(participantB, event.recipientId());
        assertNotNull(event.messageId());
    }

    /**
     * B-04 (0.4 — the CodeRabbit round-1 root adoption): ONE KEY SPACE PER
     * SENDER. A client-chosen key such as "msg-2026-10-07-001" is
     * realistic to collide across senders — the second sender's message is
     * LEGITIMATE, so his own key space answers 201 with his own new
     * message (never the first sender's 403, which also leaked that
     * another user had burned the key). The lookup and the V150
     * uq_messages_sender_idempotency_key constraint share the same
     * (sender_id, idempotency_key) scope.
     */
    @Test
    void sendMessage_sameKeyOfAnotherSender_isHisOwnNewMessage() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();
        Message firstSendersMessage = Message.create(conv.getId(), participantA, "Hello!", "msg-2026-10-07-001");

        // participantB's own key space is EMPTY for the same literal key...
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(messageRepository.findBySenderIdAndIdempotencyKey(participantB, "msg-2026-10-07-001"))
                .thenReturn(Optional.empty());
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageMapper.toResponse(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            return new MessageResponse(saved.getId(), saved.getConversationId(), saved.getSenderId(),
                    saved.getContent(), saved.isRead(), saved.getCreatedAt(), saved.getUpdatedAt());
        });

        // ...so his send lands as HIS new message — the arrival fact goes
        // to participantA (the other participant), never to himself.
        MessagingService.SendMessageOutcome outcome =
                service.sendMessage(conv.getId(), participantB, "mine now", "msg-2026-10-07-001");

        assertTrue(outcome.newlyCreated());
        assertEquals(participantB, outcome.message().senderId());
        org.mockito.ArgumentCaptor<Object> eventCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MessageReceivedEvent event = (MessageReceivedEvent) eventCaptor.getValue();
        assertEquals(participantA, event.recipientId());
        // the first sender's row was never touched — his key space is intact
        org.mockito.ArgumentCaptor<Message> savedCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).saveAndFlush(savedCaptor.capture());
        assertEquals(participantB, savedCaptor.getValue().getSenderId());
        assertEquals("msg-2026-10-07-001", savedCaptor.getValue().getIdempotencyKey());
    }

    /**
     * B-04 (0.4 — the CodeRabbit round-1 root adoption): the in-flight
     * race. Two concurrent same-key requests both miss the replay lookup;
     * the loser's INSERT takes the unique violation at ITS OWN flush (the
     * MessageSendWriter REQUIRES_NEW unit) — and the loser still gets the
     * documented 200 replay: the catch re-reads the winner's committed row
     * in a fresh transaction. Never a 409/500-flavored error for a
     * legitimate retry, never a duplicate broadcast, never a second
     * arrival fact.
     */
    @Test
    void sendMessage_concurrentSameKeyRace_loserReplaysTheWinnersRow() {
        UUID participantA = Instancio.create(UUID.class);
        UUID participantB = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), participantA)
                .set(field(Conversation::getParticipantB), participantB)
                .set(field(Conversation::getBookingId), null)
                .create();
        Message winner = Message.create(conv.getId(), participantA, "Hello!", "msg-2026-10-07-001");

        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        // first read: the race's both-miss; second read (the writer's fresh
        // replay transaction): the winner's committed row
        when(messageRepository.findBySenderIdAndIdempotencyKey(participantA, "msg-2026-10-07-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(messageRepository.saveAndFlush(any(Message.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "uq_messages_sender_idempotency_key lost the race"));
        when(messageMapper.toResponse(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            return new MessageResponse(saved.getId(), saved.getConversationId(), saved.getSenderId(),
                    saved.getContent(), saved.isRead(), saved.getCreatedAt(), saved.getUpdatedAt());
        });

        MessagingService.SendMessageOutcome outcome =
                service.sendMessage(conv.getId(), participantA, "Hello! (raced)", "msg-2026-10-07-001");

        assertFalse(outcome.newlyCreated());
        assertEquals(winner.getId(), outcome.message().id());
        // the loser never re-broadcasts (the winner's push already reached
        // the topic) and never re-publishes the arrival fact
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        verify(eventPublisher, never()).publishEvent(any());
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
        when(messageRepository.countByConversationIdAndSenderIdNotAndReadFalse(conversationId, userId)).thenReturn(5L);

        long count = service.getUnreadCount(conversationId, userId);

        assertEquals(5L, count);
    }

    /**
     * B-03 (compliance plan 0.3 — the measured defect §3.4-2): the caller's
     * own sent messages never count toward her badge. The service must
     * hand the CALLER's id to the sender-excluding derived method — the
     * repository derivation (SenderIdNot) is the Data JPA documented
     * contract; the app-level IT (DirectConversationModuleIntegrationTest)
     * proves the end-to-end badge against a real database.
     */
    @Test
    void getUnreadCount_excludesTheCallersOwnSentMessages() {
        UUID conversationId = Instancio.create(UUID.class);
        UUID userId = Instancio.create(UUID.class);
        Conversation conv = Instancio.of(Conversation.class)
                .set(field(Conversation::getParticipantA), userId)
                .set(field(Conversation::getBookingId), null)
                .create();

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationIdAndSenderIdNotAndReadFalse(conversationId, userId)).thenReturn(0L);

        long count = service.getUnreadCount(conversationId, userId);

        // She sent the only unread message → her badge is 0: the caller's id
        // is the EXCLUDED sender argument, not just a participation check.
        assertEquals(0L, count);
        verify(messageRepository).countByConversationIdAndSenderIdNotAndReadFalse(conversationId, userId);
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
