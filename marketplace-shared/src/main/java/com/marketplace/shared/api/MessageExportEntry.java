package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one message the requester <em>sent</em> ("رسائله
 * المرسلة" — the plan's provenance rule: what he authored himself). The
 * counterparty's messages in the same conversation are deliberately absent
 * — they are the counterparty's authored content, not the requester's
 * data. The conversation reference keeps the message interpretable while
 * the conversation entry carries the counterparty as an opaque UUID.
 */
public record MessageExportEntry(
        UUID id,
        UUID conversationId,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
