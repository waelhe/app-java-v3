package com.marketplace.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Review round 1 (the payments trio): the journal writer for
 * dispute-scoped remote refund operations. Every method runs in its own
 * {@code REQUIRES_NEW} transaction — the journal MUST survive the
 * resolve transaction's rollback window, which is exactly the window
 * where the remote refund executed at the provider while every local
 * write (the books, the dispute row, the resolution) died.
 *
 * <p>The Spring proxy note: this is a dedicated bean precisely because
 * {@code REQUIRES_NEW} never applies through a self-invocation — the
 * payments service calls THROUGH the proxy, and each journal write
 * commits or rolls back on its own.</p>
 */
@Service
public class PaymentRefundOperationJournal {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundOperationJournal.class);

    private final PaymentRefundOperationRepository repository;

    PaymentRefundOperationJournal(PaymentRefundOperationRepository repository) {
        this.repository = repository;
    }

    /**
     * The read-only lookup of an already-submitted operation — no creation,
     * no independent transaction (review round 2, the freeze-order finding):
     * the caller validates the refund request (the cap) BEFORE the first
     * VALID submission freezes anything, so an over-cap first request must
     * never reach the find-or-create path and poison the record.
     */
    public PaymentRefundOperation findSubmitted(String idempotencyKey) {
        return repository.findById(idempotencyKey).orElse(null);
    }

    /**
     * The submission freeze: returns the operation's PERSISTED record —
     * the first submission's row, whatever later submissions carry. The
     * first caller's amount and books-snapshot are the frozen truth every
     * retry is checked against; the transaction commits immediately, so
     * the freeze outlives the caller's own rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentRefundOperation ensureSubmitted(String idempotencyKey, UUID paymentId,
                                                  Long amountCents, long booksAtSubmitCents) {
        return repository.findById(idempotencyKey).orElseGet(() -> {
            PaymentRefundOperation born =
                    new PaymentRefundOperation(idempotencyKey, paymentId, amountCents, booksAtSubmitCents);
            PaymentRefundOperation persisted = repository.save(born);
            log.info("Dispute refund operation submitted: key={}, payment={}, amount={} cents, books-at-submit={} cents",
                    idempotencyKey, paymentId, amountCents, booksAtSubmitCents);
            return persisted;
        });
    }

    /**
     * The provider's answer: the remote refund executed — its identifier
     * is the durable local identity that outlives the provider's own
     * 24-hour idempotency-record retention.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExecuted(String idempotencyKey, String remoteRefundId) {
        repository.findById(idempotencyKey).ifPresent(operation -> {
            if ("EXECUTED".equals(operation.getStatus())) {
                log.debug("Dispute refund operation {} already recorded EXECUTED (refund {})",
                        idempotencyKey, operation.getRemoteRefundId());
                return;
            }
            operation.markExecuted(remoteRefundId);
            log.info("Dispute refund operation executed: key={}, remote refund={}",
                    idempotencyKey, remoteRefundId);
        });
    }
}
