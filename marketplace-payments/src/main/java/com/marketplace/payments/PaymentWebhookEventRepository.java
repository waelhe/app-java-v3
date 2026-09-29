package com.marketplace.payments;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID>, RevisionRepository<PaymentWebhookEvent, UUID, Integer> {
    /**
     * Provider-scoped dedup lookup (B5): the unique key is
     * {@code (provider, event_id)} (V42), so the pre-check and the
     * post-DataIntegrityViolation re-check must both be scoped to the channel —
     * the same {@code event_id} under a different provider is a different event.
     */
    Optional<PaymentWebhookEvent> findByProviderAndEventId(String provider, String eventId);

    /**
     * Compensating delete used by {@link WebhookEventRecorder#delete} after a
     * failed dispatch — removes the dedup row so the provider retry
     * re-processes the event (CodeRabbit #241). Provider-scoped like the gate it
     * compensates (B5).
     */
    void deleteByProviderAndEventId(String provider, String eventId);

    /**
     * R10 — the recovery sweep's read: inbox rows still RECEIVED whose record
     * committed before the cutoff, i.e. every row old enough that no original
     * delivery can still be mid-dispatch (the staleness threshold exceeds any
     * dispatch's lifetime). Served by V71's partial recovery index.
     */
    List<PaymentWebhookEvent> findByProcessingStateAndCreatedAtBefore(
            WebhookProcessingState processingState, Instant cutoff);
}
