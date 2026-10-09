package com.marketplace.ai;

import com.marketplace.knowledge.KnowledgeEntryPublishedEvent;
import com.marketplace.knowledge.KnowledgeEntryWithdrawnEvent;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Maintains the AI knowledge index from the knowledge module's complete
 * publication events. Spring Modulith dispatches these listeners after the
 * business transaction commits and in a separate transaction; failed indexing
 * therefore remains retryable through the event publication registry.
 */
public class AiKnowledgeEntryEventListener {

    private final AiKnowledgeGateway knowledgeGateway;

    public AiKnowledgeEntryEventListener(AiKnowledgeGateway knowledgeGateway) {
        this.knowledgeGateway = knowledgeGateway;
    }

    @ApplicationModuleListener
    public void onKnowledgeEntryPublished(KnowledgeEntryPublishedEvent event) {
        String content = """
                Neighborhood knowledge entry
                Category: %s
                Location ID: %s
                Title: %s

                %s
                """.formatted(
                event.category().name(),
                event.locationId(),
                event.title(),
                event.body());

        knowledgeGateway.replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                event.entryId().toString(),
                "knowledge-entry",
                content));
    }

    @ApplicationModuleListener
    public void onKnowledgeEntryWithdrawn(KnowledgeEntryWithdrawnEvent event) {
        // The tombstone (not a bare delete): the durable withdrawal fact that
        // also blocks a late retried/replayed publication of the same entry
        // from re-exposing it in the public index.
        knowledgeGateway.markWithdrawn(event.entryId().toString());
    }
}
