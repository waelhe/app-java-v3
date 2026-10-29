package com.marketplace.payments;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.ApiProblemDetailException;

/**
 * The dispute refund's books-sync wait — the ONE conflict that is a WAIT,
 * not a refusal (CodeRabbit review round on #741's part 4, 2026-10-28).
 *
 * <p>The gate's own history: the operation's EXECUTED record proves the
 * provider already moved the money, but the local books advance only when
 * the {@code charge.refunded} webhook lands; a retry that arrives inside
 * that window must come BACK, not fail terminally. The pre-fix shape threw
 * {@link com.marketplace.shared.api.ConflictException} whose message said
 * "retry after the webhook lands" — while the {@code paymentProcessing}
 * retry instance lists ConflictException among its
 * {@code ignore-exceptions}, so the annotated edge never retried its own
 * documented retry condition (Resilience4j's ignore matching is
 * assignable: a subclass would be ignored with its parent, so this type
 * deliberately extends NOTHING in the ConflictException hierarchy).
 *
 * <p>Wire shape: unchanged for every caller — the taxonomy is CONFLICT, so
 * the RFC 9457 body renders the same 409 the ConflictException gate always
 * answered (through the official {@code ErrorResponseException} carrier
 * every house domain exception rides); the GraphQL envelope and the logs
 * keep the detail sentence. Only the RETRY behavior changes: the type is
 * absent from the ignore list, so {@code @Retry(name = "paymentProcessing")}
 * absorbs the webhook-latency window with its own backoff, and the
 * exhaustion surface stays the honest 409 the caller already knew.
 *
 * <p>The sibling ConflictException guards (the amount freeze, the caps)
 * keep their non-retryable contract exactly as before: an identical
 * re-submission re-fails identically, and burning the shared retry budget
 * on a caller-controlled refusal stays wrong.
 */
public class RefundBooksSyncPendingException extends ApiProblemDetailException {

    public RefundBooksSyncPendingException(String detail) {
        super(ApiErrorTaxonomy.CONFLICT, detail);
    }
}
