package com.marketplace.ledger;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import io.micrometer.observation.annotation.Observed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class LedgerJournalService {
    private static final String SRC="DELIVERY", CLEARING="PSP_CLEARING",
            ADVANCES="DELIVERY_CUSTOMER_ADVANCES", REVENUE="DELIVERY_REVENUE",
            REFUNDS="DELIVERY_REFUNDS", COURIER_PAYABLE="DELIVERY_COURIER_PAYABLE";

    /**
     * A3 (adversarial review 2026-11-10, owner-gate batch 6a — the documented
     * recommendation): the refund-delta critical section's transaction-scoped
     * advisory lock, ONE key per delivery batch — the
     * {@code LOCK_ACTIVE_ADMIN_INVARIANT} shape (UserService, enforced through
     * #818) with the per-batch derivation this read-modify-write needs.
     * PostgreSQL official docs, §13.3.5 Advisory Locks: «pg_advisory_xact_lock
     * works the same as pg_advisory_lock, except the lock is automatically
     * released at the end of the current transaction» — a transaction-scoped
     * lock has NO forgotten-unlock path by construction. The lock serializes
     * the WHOLE critical section (the {@code findTopBy…(cumulative DESC)} read,
     * the delta computation, and the insert) that the pre-fix shape left
     * racing: two concurrent webhook snapshots (cumulative 300 then 700) both
     * read {@code previous = 0} and over-posted to PSP_CLEARING (1000 posted
     * for an actual 700 — the ledger's own finding A3). The scope qualifier
     * keeps distinct batches independent — only the same batch's refunds
     * serialize.
     */
    private static final String REFUND_DELTA_LOCK_SQL_PREFIX =
            "SELECT pg_advisory_xact_lock(hashtextextended('marketplace.delivery-refund-delta:' || '";

    private final LedgerJournalPostingRepository postings;
    private final JdbcTemplate jdbcTemplate;

    public LedgerJournalService(LedgerJournalPostingRepository postings, JdbcTemplate jdbcTemplate) {
        this.postings = postings;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Observed(name="ledger.delivery.payment.capture")
    public void capture(PaymentIntentDetails payment) {
        if (!payment.isDeliveryOrigin() || payment.deliveryRequestId()==null) {
            throw new IllegalArgumentException("Delivery payment origin is required");
        }
        validateMoney(payment.amountCents(), payment.currency());
        addIfAbsent(payment.deliveryRequestId(), payment.paymentIntentId(), "CAPTURE",
                CLEARING, ADVANCES, payment.amountCents(), payment.currency(), null, null,
                "Customer delivery amount collected");
    }

    @Observed(name="ledger.delivery.revenue.recognize")
    public void recognize(DeliveryCompletedEvent event) {
        if (event.deliveryRequestId()==null || event.paymentIntentId()==null) {
            throw new IllegalArgumentException("Delivery/payment ids are required");
        }
        validateMoney(event.amountMinor(), event.currency());
        if (event.courierCompensationMinor()<0 || event.courierCompensationMinor()>=event.amountMinor()) {
            throw new IllegalArgumentException("Courier compensation must be nonnegative and below the fare");
        }
        if (event.courierCompensationMinor()>0 && event.courierUserId()==null) {
            throw new IllegalArgumentException("Courier identity is required for a courier payable");
        }
        LedgerJournalPosting capture=postings.findBySourceTypeAndSourceIdAndOperationKey(
                SRC,event.deliveryRequestId(),"CAPTURE")
                .orElseThrow(()->new IllegalStateException("Cannot recognize revenue until the capture journal entry exists"));
        if (!capture.getPaymentIntentId().equals(event.paymentIntentId())
                || capture.getAmountMinor()!=event.amountMinor()
                || !capture.getCurrency().equals(event.currency())) {
            throw new IllegalStateException("Delivery completion does not match captured payable");
        }
        addIfAbsent(event.deliveryRequestId(),event.paymentIntentId(),"RECOGNIZE",
                ADVANCES,REVENUE,event.amountMinor(),event.currency(),null,null,
                "Delivery completed; recognize gross earned revenue");
        if (event.courierCompensationMinor()>0) {
            addIfAbsent(event.deliveryRequestId(),event.paymentIntentId(),"COURIER_PAYABLE",
                    REVENUE,COURIER_PAYABLE,event.courierCompensationMinor(),event.currency(),
                    null,event.courierUserId(),"Courier compensation earned on delivery completion");
        }
    }

    @Observed(name="ledger.delivery.payment.refund")
    public void refund(PaymentIntentDetails payment) {
        if (!payment.isDeliveryOrigin() || payment.deliveryRequestId()==null) {
            throw new IllegalArgumentException("Delivery payment origin is required");
        }
        long cumulative=payment.refundedAmountCents();
        if (cumulative<=0) return;
        if (cumulative>payment.amountCents()) throw new IllegalStateException("Refund exceeds captured amount");
        validateMoney(payment.amountCents(),payment.currency());
        // A3: the advisory lock precedes the read — the whole critical section
        // (capture replay, latest-cumulative read, delta computation, insert)
        // runs inside the transaction bounded by the lock. The id is
        // java.util.UUID: its toString is strictly [0-9a-f-] — the
        // concatenation is structurally injection-safe, and the SAME
        // derivation shape guards the last-admin invariant (UserService).
        jdbcTemplate.execute(
                REFUND_DELTA_LOCK_SQL_PREFIX + payment.deliveryRequestId() + "', 0))");
        capture(payment);
        long previous=postings.findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc(
                SRC,payment.deliveryRequestId()).map(LedgerJournalPosting::getCumulativeRefundMinor).orElse(0L);
        if (cumulative<=previous) return;
        boolean recognized=postings.findBySourceTypeAndSourceIdAndOperationKey(
                SRC,payment.deliveryRequestId(),"RECOGNIZE").isPresent();
        addIfAbsent(payment.deliveryRequestId(),payment.paymentIntentId(),"REFUND:"+cumulative,
                recognized?REFUNDS:ADVANCES,CLEARING,cumulative-previous,payment.currency(),cumulative,null,
                "Refund delta through cumulative total "+cumulative);
    }

    private void addIfAbsent(UUID source,UUID payment,String operation,String debit,String credit,
                             long amount,String currency,Long cumulative,UUID recipient,String memo) {
        validateMoney(amount,currency);
        if (postings.findBySourceTypeAndSourceIdAndOperationKey(SRC,source,operation).isPresent()) return;
        postings.save(new LedgerJournalPosting(UUID.randomUUID(),SRC,source,payment,operation,
                debit,credit,amount,currency.toUpperCase(Locale.ROOT),cumulative,recipient,memo));
    }
    private static void validateMoney(long amount,String currency) {
        if (amount<=0 || currency==null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Positive amount and uppercase ISO currency are required");
        }
    }
}
