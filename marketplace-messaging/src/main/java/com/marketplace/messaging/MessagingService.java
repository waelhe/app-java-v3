package com.marketplace.messaging;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class MessagingService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BookingParticipantProvider bookingParticipantProvider;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            BookingParticipantProvider bookingParticipantProvider) {
        this(conversationRepository, messageRepository, bookingParticipantProvider, null);
    }

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            BookingParticipantProvider bookingParticipantProvider,
                            SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional(readOnly = true)
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
        return messageRepository.countByConversationIdAndReadFalse(conversationId);
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
        return conversationRepository.save(Conversation.create(participantA, participantB, bookingId));
    }

    public Message sendMessage(UUID conversationId, UUID senderId, String content) {
        getConversation(conversationId, senderId);
        Message message = messageRepository.save(Message.create(conversationId, senderId, content));
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/conversations/" + conversationId, MessageResponse.from(message));
        }
        return message;
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
