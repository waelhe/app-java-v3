package com.marketplace.identity;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.shared.api.ResourceNotFoundException;

/**
 * The follow create's write unit, in its own REQUIRES_NEW transaction —
 * the {@code MessageSendWriter} house pattern (B-04's CodeRabbit root
 * adoption), applied to the follow insert (CodeRabbit round on the #540
 * slice).
 *
 * <p><b>Why a separate transaction for the INSERT:</b> the endpoint's
 * documented idempotent contract promises that a replay — including the
 * in-flight race where two concurrent same-triple requests both miss the
 * lookup — answers the STANDING ROW, never an error. The loser of that
 * race learns about it from a {@code DataIntegrityViolationException} at
 * flush, and in PostgreSQL the transaction that took the violation is
 * aborted (rollback-only): no statement can run inside it anymore, so
 * neither the catch nor the re-read can live in the caller's
 * {@code REQUIRED} transaction. Running the INSERT here, in its own
 * transaction, keeps the caller's context healthy: the inner transaction
 * alone rolls back, the catch re-reads the winner's committed row in a
 * fresh transaction ({@link #replay}), and the API answers the promised
 * 200-with-existing-row — never a 500-flavored surprise for a legitimate
 * concurrent retry.
 */
@Component
class FollowWriter {

    private final FollowRepository followRepository;

    FollowWriter(FollowRepository followRepository) {
        this.followRepository = followRepository;
    }

    /**
     * The INSERT itself — flushed inside the method so the unique
     * violation surfaces HERE (on this transaction), not at the caller's
     * commit where no catch could answer it with the standing row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Follow persist(Follow follow) {
        return followRepository.saveAndFlush(follow);
    }

    /**
     * The race loser's re-read, in a fresh transaction: by the time the
     * loser sees the unique violation, the winner's row is committed
     * (PostgreSQL's unique-conflict semantics — an in-flight conflicting
     * INSERT makes the loser WAIT until the winner commits or aborts), so
     * this read sees it. Same (user, type, source) scope as the lookup
     * and the {@code uq_follows_one_live} constraint.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Follow replay(UUID userId, FollowableType type, UUID sourceId) {
        return followRepository
                .findByUserIdAndFollowableTypeAndFollowableId(userId, type, sourceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Follow not found: " + sourceId));
    }
}
