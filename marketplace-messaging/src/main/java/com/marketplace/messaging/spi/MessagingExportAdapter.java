package com.marketplace.messaging.spi;

import com.marketplace.messaging.Conversation;
import com.marketplace.messaging.ConversationRepository;
import com.marketplace.messaging.Message;
import com.marketplace.messaging.MessageRepository;
import com.marketplace.shared.api.ConversationExportEntry;
import com.marketplace.shared.api.MessageExportEntry;
import com.marketplace.shared.api.MessagingExportData;
import com.marketplace.shared.api.MessagingExportPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the messaging module's implementation of the
 * {@link MessagingExportPort} cross-module contract. A read-only
 * aggregation of the plan's provenance scope: the conversations the
 * requester participates in (each with the counterparty as an opaque UUID)
 * plus <em>only the messages he sent</em> — the counterparty's messages
 * are his counterpart's authored content and stay out of the export.
 *
 * <p>The delegate boundary note: this adapter reads the repositories
 * directly rather than going through {@code MessagingService} — the
 * service's surface is conversational (paged window reads behind
 * authorization managers), while the export needs the full deterministic
 * history; both read the same tables the module owns.
 */
@Component
@Transactional(readOnly = true)
public class MessagingExportAdapter implements MessagingExportPort {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public MessagingExportAdapter(ConversationRepository conversationRepository,
                                  MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public MessagingExportData exportForParticipant(UUID userId) {
        List<ConversationExportEntry> conversations = conversationRepository
                .findAllByParticipantAOrParticipantBOrderByCreatedAtAsc(userId, userId)
                .stream()
                .map(conversation -> toEntry(conversation, userId))
                .toList();
        List<MessageExportEntry> messages = messageRepository
                .findAllBySenderIdOrderByCreatedAtAsc(userId)
                .stream()
                .map(MessagingExportAdapter::toEntry)
                .toList();
        return new MessagingExportData(conversations, messages);
    }

    private static ConversationExportEntry toEntry(Conversation conversation, UUID userId) {
        // Counterparty minimality: the other participant as an opaque UUID —
        // participantA on the B side, participantB on the A side.
        UUID counterparty = userId.equals(conversation.getParticipantA())
                ? conversation.getParticipantB()
                : conversation.getParticipantA();
        return new ConversationExportEntry(
                conversation.getId(),
                counterparty,
                conversation.getBookingId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    private static MessageExportEntry toEntry(Message message) {
        return new MessageExportEntry(
                message.getId(),
                message.getConversationId(),
                message.getContent(),
                message.getCreatedAt(),
                message.getUpdatedAt());
    }
}
