package com.marketplace.messaging;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.NeighborhoodTrustLookupPort;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@NamedInterface("messaging-api")
@Service
@Transactional
public class MessagingService {

    private static final Set<String> CONVERSATION_CACHE_NAMES = Set.of("conversations");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final UserLookupPort userLookupPort;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final NeighborhoodTrustLookupPort neighborhoodTrustLookupPort;

    @Autowired
    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            BookingParticipantProvider bookingParticipantProvider,
                            UserLookupPort userLookupPort,
                            SimpMessagingTemplate messagingTemplate,
                            MessageMapper messageMapper,
                            ApplicationEventPublisher eventPublisher,
                            NeighborhoodTrustLookupPort neighborhoodTrustLookupPort) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.userLookupPort = userLookupPort;
        this.messagingTemplate = messagingTemplate;
        this.messageMapper = messageMapper;
        this.eventPublisher = eventPublisher;
        this.neighborhoodTrustLookupPort = neighborhoodTrustLookupPort;
    }

    /** Compatibility constructor retained for focused unit tests of booking chat. */
    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            BookingParticipantProvider bookingParticipantProvider,
                            UserLookupPort userLookupPort,
                            SimpMessagingTemplate messagingTemplate,
                            MessageMapper messageMapper,
                            ApplicationEventPublisher eventPublisher) {
        this(conversationRepository, messageRepository, bookingParticipantProvider, userLookupPort,
                messagingTemplate, messageMapper, eventPublisher,
                ignored -> NeighborhoodTrustLookupPort.TrustState.UNVERIFIED);
    }

    @Transactional(readOnly = true)
    @Cacheable("conversations")
    public Conversation getConversation(UUID id, UUID userId) {
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + id));
        verifyParticipant(conversation, userId);
        return conversation;
    }

    @Transactional(readOnly = true)
    public Page<Message> getMessages(UUID conversationId, UUID userId, Pageable pageable) {
        getConversation(conversationId, userId);
        return messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID conversationId, UUID userId) {
        getConversation(conversationId, userId);
        // B-03 (0.3): the badge is what the CALLER still owes a read — her
        // own sent messages never count toward it (the sender-exclusion the
        // bulk mark-read below has carried all along; the counter was the
        // drifted half of the pair).
        return messageRepository.countByConversationIdAndSenderIdNotAndReadFalse(conversationId, userId);
    }

    public Conversation createConversation(UUID participantA, UUID bookingId) {
        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);
        bookingInfo.requireParticipant(participantA);
        bookingInfo.requireStatusNot("CANCELLED", "create conversation");

        // Reuse existing conversation for same booking
        var existing = conversationRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            Conversation conversation = existing.get();
            verifyParticipant(conversation, participantA);
            return conversation;
        }

        UUID participantB = bookingInfo.providerId().equals(participantA)
                ? bookingInfo.consumerId()
                : bookingInfo.providerId();
        Conversation saved = conversationRepository.save(Conversation.create(participantA, participantB, bookingId));
        eventPublisher.publishEvent(new CacheInvalidationRequested(CONVERSATION_CACHE_NAMES));
        return saved;
    }

    /**
     * L44 (neighborhood community plan §5 — direct neighbor messages):
     * opens — or returns — the direct conversation between the caller and
     * one recipient, the booking-less channel on the same
     * {@code conversations} table (D-N8: extend the existing table, never a
     * parallel one; {@code booking_id IS NULL} is the direct marker — V7
     * measured the column nullable from day one).
     *
     * <p><b>Gates before any write (the plan's own words):</b> a conversation
     * with yourself is {@link BadRequestException} (400 — the request is the
     * caller's own id, a client-contract violation, never a server state);
     * a recipient that does not exist is
     * {@link ResourceNotFoundException} (404 — the honest miss through the
     * identity seam {@link UserLookupPort}, the same seam notifications and
     * reviews already resolve users through).
     *
     * <p><b>Canonical pair ordering ({@code UUID.compareTo}):</b> the two
     * participants are always stored in the same canonical order —
     * {@code participantA} is the pair's {@code compareTo}-first,
     * {@code participantB} the {@code compareTo}-second — so whoever of
     * the two opens (or re-opens) the conversation, the stored pair is
     * byte-identical (the plan's reversed-order criterion). Note the
     * contract's own shape: {@code UUID.compareTo} compares the two
     * long halves SIGNED, so it is a total order that need not match
     * byte-wise unsigned order — irrelevant here, because the canonical
     * order only has to be a deterministic function of the unordered
     * pair. The pair equality finder rides the V7
     * {@code idx_conversations_participants} partial index; the V67
     * partial unique index on {@code (LEAST(..), GREATEST(..))} is the
     * DB-level guard for the same invariant on the BYTE representation —
     * an independent guard that catches a duplicate pair in ANY column
     * order — a concurrent double-open loses one racer to 23505 and the
     * house translation answers 409 (the L30 G-N1 precedent: the explicit
     * pre-check first, the constraint the backstop).
     *
     * <p><b>Idempotent reuse (the {@code findByBookingId} precedent
     * verbatim):</b> an existing direct conversation for the same pair is
     * returned as-is — the controller answers 200 for it and 201 only for a
     * newly created one ({@link DirectConversationOutcome#newlyCreated()}).
     * Every existing access point (read, messages, unread, WebSocket
     * subscription authorization) verifies participation, never the
     * conversation's type — booking conversations and direct conversations
     * are the same rows to them (the non-breakage gate).
     *
     * <p><b>No messaging↔community coupling (the plan's documented
     * decision):</b> the direct channel is a horizontal general channel —
     * exactly like the booking one — and recipient discovery lives in the
     * client's neighborhood context, not in a boundary-crossing membership
     * check here.
     */
    public DirectConversationOutcome openDirectConversation(UUID requesterId, UUID recipientId) {
        if (neighborhoodTrustLookupPort.trustFor(requesterId)
                == NeighborhoodTrustLookupPort.TrustState.REJECTED) {
            throw new AccessDeniedException("Rejected neighborhood verification cannot open direct conversations");
        }
        if (requesterId.equals(recipientId)) {
            throw new BadRequestException("Cannot open a direct conversation with yourself");
        }
        userLookupPort.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + recipientId));

        UUID participantA = requesterId.compareTo(recipientId) < 0 ? requesterId : recipientId;
        UUID participantB = requesterId.compareTo(recipientId) < 0 ? recipientId : requesterId;

        var existing = conversationRepository
                .findFirstByBookingIdIsNullAndParticipantAAndParticipantB(participantA, participantB);
        if (existing.isPresent()) {
            return new DirectConversationOutcome(existing.get(), false);
        }

        Conversation saved = conversationRepository.save(Conversation.create(participantA, participantB, null));
        eventPublisher.publishEvent(new CacheInvalidationRequested(CONVERSATION_CACHE_NAMES));
        return new DirectConversationOutcome(saved, true);
    }

    /**
     * The L44 open-direct outcome: the conversation plus whether this call
     * created it (201) or returned an existing one (200 — the idempotent
     * reuse the plan requires; the booking flow's single 201 always made
     * the distinction invisible, this surface makes it explicit).
     */
    public record DirectConversationOutcome(Conversation conversation, boolean newlyCreated) {
    }

    /**
     * The WebSocket path's send (interactive — no replay key on the socket;
     * the REST path carries the caller's deduplication surface).
     */
    public MessageResponse sendMessage(UUID conversationId, UUID senderId, String content) {
        return sendMessage(conversationId, senderId, content, null).message();
    }

    /**
     * B-04 (compliance plan 0.4 — Data JPA jpa/locking.html): the send is
     * idempotent on the caller's replay key — a retried submission returns
     * the ORIGINAL message instead of a duplicate row (the payment_intents
     * contract mirrored exactly: same key + same sender → replay; a key
     * belonging to another sender is 403). The @Version optimistic lock
     * (BaseEntity, on every message row) plus V150's UNIQUE index close the
     * in-flight race: the concurrent same-key double-submit loses at flush,
     * never a duplicate row.
     */
    @Observed(name = "messaging.send")
    public SendMessageOutcome sendMessage(UUID conversationId, UUID senderId, String content, String idempotencyKey) {
        getConversation(conversationId, senderId);
        if (idempotencyKey != null) {
            Optional<Message> existing = messageRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().getSenderId().equals(senderId)) {
                    throw new AccessDeniedException("Idempotency key belongs to another sender");
                }
                // Replay: the original WebSocket push already reached the
                // topic — a second broadcast would duplicate it for every
                // other subscriber.
                return new SendMessageOutcome(messageMapper.toResponse(existing.get()), false);
            }
        }
        Message saved = messageRepository.save(Message.create(conversationId, senderId, content, idempotencyKey));
        MessageResponse response = messageMapper.toResponse(saved);
        messagingTemplate.convertAndSend("/topic/conversations/" + conversationId, response);
        return new SendMessageOutcome(response, true);
    }

    /**
     * The send's 201/200 outcome — {@code newlyCreated} carries the same
     * distinction {@link DirectConversationOutcome} made explicit for the
     * L44 direct-open surface (201 the first time, 200 the idempotent
     * replay).
     */
    public record SendMessageOutcome(MessageResponse message, boolean newlyCreated) {
    }

    public void markAsRead(UUID conversationId, UUID userId) {
        getConversation(conversationId, userId);
        messageRepository.markAsReadByConversationId(conversationId, userId);
    }

    private void verifyParticipant(Conversation conversation, UUID userId) {
        if (!conversation.hasParticipant(userId)) {
            throw new AccessDeniedException("User is not a participant in this conversation");
        }
    }
}
