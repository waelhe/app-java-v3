package com.marketplace.ai;

import com.marketplace.shared.api.KnowledgeEntryPublishedEvent;
import com.marketplace.shared.api.KnowledgeEntryWithdrawnEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiKnowledgeEntryEventListenerTest {

    @Test
    void publishedKnowledgeEntryIsIndexedWithStableSourceIdentity() {
        AiKnowledgeGateway gateway = mock(AiKnowledgeGateway.class);
        AiKnowledgeEntryEventListener listener = new AiKnowledgeEntryEventListener(gateway);
        UUID entryId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        listener.onKnowledgeEntryPublished(new KnowledgeEntryPublishedEvent(
                entryId,
                locationId,
                "PLACES",
                "Local landmarks",
                "The neighborhood has a public garden.",
                UUID.randomUUID()));

        ArgumentCaptor<AiKnowledgeGateway.AiKnowledgeSource> captor =
                ArgumentCaptor.forClass(AiKnowledgeGateway.AiKnowledgeSource.class);
        verify(gateway).replacePublicSource(captor.capture());

        AiKnowledgeGateway.AiKnowledgeSource source = captor.getValue();
        assertThat(source.sourceId()).isEqualTo(entryId.toString());
        assertThat(source.sourceType()).isEqualTo("knowledge-entry");
        assertThat(source.content()).contains(
                "PLACES",
                locationId.toString(),
                "Local landmarks",
                "The neighborhood has a public garden.");
    }

    @Test
    void withdrawnKnowledgeEntryIsMarkedWithTheDurableTombstone() {
        AiKnowledgeGateway gateway = mock(AiKnowledgeGateway.class);
        AiKnowledgeEntryEventListener listener = new AiKnowledgeEntryEventListener(gateway);
        UUID entryId = UUID.randomUUID();

        listener.onKnowledgeEntryWithdrawn(new KnowledgeEntryWithdrawnEvent(entryId, UUID.randomUUID()));

        // markWithdrawn (not a bare delete): the tombstone also blocks any late
        // retried/replayed publication of the same entry (the reversed-order race)
        verify(gateway).markWithdrawn(entryId.toString());
        verify(gateway, org.mockito.Mockito.never()).replacePublicSource(any());
    }
}
