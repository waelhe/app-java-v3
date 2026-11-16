package com.marketplace.shared.api;

import java.util.List;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the messaging module's share of a data-subject
 * export — the conversations the requester participates in (each with the
 * counterparty as an opaque UUID) plus the messages he sent within them.
 * The two halves travel together so the aggregation stays a single
 * cross-module call per section.
 */
public record MessagingExportData(
        List<ConversationExportEntry> conversations,
        List<MessageExportEntry> messages
) {
}
