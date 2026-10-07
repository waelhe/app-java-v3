package com.marketplace.messaging;

import java.util.UUID;

/**
 * B-08 (compliance plan 0.10 — Modulith reference/events.html): a message
 * landed in a conversation — the arrival fact for the OTHER participant.
 * Published by {@code MessagingService.sendMessage} on the module's
 * exposed {@code messaging} NamedInterface; the notifications-side
 * listener that turns it into the recipient's arrival notification rides
 * CR-4 (the event type's cross-module placement — see the worklog).
 *
 * <p>The payload is the complete arrival fact (the house's lean event
 * shape — business ids only): the message, its conversation, who sent it,
 * and who should learn about it. The recipient is resolved at the source
 * (the publisher holds the conversation) so no consumer ever re-derives
 * party facts. Additive-only registration: a NEW event, pending the
 * contracts ledger the foundation branch will carry.
 */
public record MessageReceivedEvent(UUID messageId, UUID conversationId,
                                   UUID senderId, UUID recipientId) {
}
