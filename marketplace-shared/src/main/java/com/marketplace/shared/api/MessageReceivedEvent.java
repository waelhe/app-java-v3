package com.marketplace.shared.api;

import java.util.UUID;

/**
 * B-08 (compliance plan 0.10 — Modulith reference/events.html): a message
 * landed in a conversation — the arrival fact for the OTHER participant.
 * Published by {@code MessagingService.sendMessage} inside the sender's own
 * transaction; the notifications-side {@code @ApplicationModuleListener}
 * consumer runs AFTER_COMMIT in its own REQUIRES_NEW unit and turns it into
 * the recipient's arrival notification.
 *
 * <p><b>Placement ruling (CR-4, decided on the foundation branch — the
 * contracts ledger §1.1):</b> this event crosses module boundaries, so its
 * record lives in {@code shared/api} — the house convention measured on the
 * eighteen cross-boundary records that precede it (BookingCreatedEvent,
 * PostCommentedEvent, SavedSearchMatchedEvent ...). The consumer-side import
 * needs NO new module dependency this way: notifications already depends on
 * shared, and no pom anywhere changes. The publisher keeps a thin re-export
 * nothing — messaging imports this record directly.
 *
 * <p>The payload is the complete arrival fact (the house's lean event shape —
 * business ids only): the message, its conversation, who sent it, and who
 * should learn about it. The recipient is resolved at the source (the
 * publisher holds the conversation) so no consumer ever re-derives party
 * facts. Additive-only registration: a NEW event, recorded in the contracts
 * ledger ({@code docs/governance/parallel-contracts-ledger.md} §1.1) by the
 * same foundation commit that carries this file.
 */
public record MessageReceivedEvent(UUID messageId, UUID conversationId,
                                   UUID senderId, UUID recipientId) {
}
