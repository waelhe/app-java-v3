package com.marketplace.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-04 (compliance plan 0.4 — CodeRabbit round-1 root adoption): the
 * send's write unit, in its own REQUIRES_NEW transaction.
 *
 * <p><b>Why a separate transaction for the INSERT:</b> the documented
 * idempotent-send contract promises a retried submission the ORIGINAL
 * message — including the in-flight race where two concurrent same-key
 * requests both miss the replay lookup. The loser of that race learns
 * about it from a {@code DataIntegrityViolationException} at flush, and
 * in PostgreSQL the transaction that took the violation is aborted: no
 * statement can run inside it anymore. Running the INSERT in its own
 * transaction keeps the caller's read/event transaction healthy, so the
 * service can catch the violation and re-read the winner's committed row
 * ({@link #replay}) in a fresh transaction — the 200 replay the API
 * promises, never a 409/500-flavored error for a legitimate retry.
 *
 * <p><b>The house's transaction-propagation vocabulary:</b> the same
 * {@code Propagation#REQUIRES_NEW} the framework documents for
 * "an independent transaction" — here scoped to the single INSERT (and
 * the single re-read) so the suspension window is one statement wide,
 * never the whole send.
 */
@Component
class MessageSendWriter {

    private final MessageRepository messageRepository;

    MessageSendWriter(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * The INSERT itself — flushed inside the method so the unique
     * violation surfaces HERE (on this transaction), not at the caller's
     * commit where no catch could answer it with a replay.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Message persist(UUID conversationId, UUID senderId, String content, String idempotencyKey) {
        return messageRepository.saveAndFlush(
                Message.create(conversationId, senderId, content, idempotencyKey));
    }

    /**
     * The race loser's re-read, in a fresh transaction: by the time the
     * loser sees the unique violation, the winner's row is committed
     * (PostgreSQL's unique-conflict semantics — an in-flight conflicting
     * INSERT makes the loser WAIT until the winner commits or aborts), so
     * this read sees it. Same (sender, key) scope as the lookup and the
     * constraint.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Message replay(UUID senderId, String idempotencyKey) {
        return messageRepository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey).orElse(null);
    }
}
