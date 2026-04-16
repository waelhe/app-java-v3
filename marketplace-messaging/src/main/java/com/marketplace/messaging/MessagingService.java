package com.marketplace.messaging;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class MessagingService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public Conversation getConversation(UUID id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Message> getMessages(UUID conversationId, Pageable pageable) {
        return messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID conversationId) {
        return messageRepository.countByConversationIdAndReadFalse(conversationId);
    }

    public Conversation createConversation(UUID participantA, UUID participantB, UUID bookingId) {
        // Reuse existing conversation for same booking
        if (bookingId != null) {
            var existing = conversationRepository.findByBookingId(bookingId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return conversationRepository.save(Conversation.create(participantA, participantB, bookingId));
    }

    public Message sendMessage(UUID conversationId, UUID senderId, String content) {
        Conversation conversation = getConversation(conversationId);
        if (!conversation.hasParticipant(senderId)) {
            throw new IllegalArgumentException("User is not a participant in this conversation");
        }
        return messageRepository.save(Message.create(conversationId, senderId, content));
    }

    public void markAsRead(UUID conversationId, UUID userId) {
        Conversation conversation = getConversation(conversationId);
        if (!conversation.hasParticipant(userId)) {
            throw new IllegalArgumentException("User is not a participant in this conversation");
        }
        messageRepository.markAsReadByConversationId(conversationId, userId);
    }
}