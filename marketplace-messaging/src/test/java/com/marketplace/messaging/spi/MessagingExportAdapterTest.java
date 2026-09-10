package com.marketplace.messaging.spi;

import com.marketplace.messaging.Conversation;
import com.marketplace.messaging.ConversationRepository;
import com.marketplace.messaging.Message;
import com.marketplace.messaging.MessageRepository;
import com.marketplace.shared.api.ConversationExportEntry;
import com.marketplace.shared.api.MessagingExportData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingExportAdapterTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final MessagingExportAdapter adapter =
            new MessagingExportAdapter(conversationRepository, messageRepository);

    @Test
    void conversationsCarryTheCounterpartyAsAnOpaqueUuidOnEitherSide() {
        UUID me = UUID.randomUUID();
        UUID otherA = UUID.randomUUID();
        UUID otherB = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        // I am participant B in the first, participant A in the second.
        Conversation onBSide = Conversation.create(otherA, me, booking);
        Conversation onASide = Conversation.create(me, otherB, null);
        when(conversationRepository.findAllByParticipantAOrParticipantBOrderByCreatedAtAsc(me, me))
                .thenReturn(List.of(onBSide, onASide));
        when(messageRepository.findAllBySenderIdOrderByCreatedAtAsc(me)).thenReturn(List.of());

        MessagingExportData data = adapter.exportForParticipant(me);

        assertEquals(2, data.conversations().size());
        ConversationExportEntry bSide = data.conversations().get(0);
        assertEquals(otherA, bSide.counterpartyUserId());
        assertEquals(booking, bSide.bookingId());
        ConversationExportEntry aSide = data.conversations().get(1);
        assertEquals(otherB, aSide.counterpartyUserId());
        assertEquals(onASide.getId(), aSide.id());
    }

    @Test
    void onlyTheMessagesHeSentAreExported() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Conversation conversation = Conversation.create(me, other, null);
        Message mine = Message.create(conversation.getId(), me, "my own words");
        // The repository query is sender-scoped by construction — the
        // counterparty's message never reaches the mapping.
        when(conversationRepository.findAllByParticipantAOrParticipantBOrderByCreatedAtAsc(me, me))
                .thenReturn(List.of(conversation));
        when(messageRepository.findAllBySenderIdOrderByCreatedAtAsc(me))
                .thenReturn(List.of(mine));

        MessagingExportData data = adapter.exportForParticipant(me);

        assertEquals(1, data.messages().size());
        assertEquals("my own words", data.messages().get(0).content());
        assertEquals(conversation.getId(), data.messages().get(0).conversationId());
        // The conversation entry contract: id, counterparty, booking, dates —
        // no participant pair, no audit columns.
        assertEquals(5, ConversationExportEntry.class.getRecordComponents().length);
    }
}
