package com.marketplace.ledger;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import io.micrometer.observation.annotation.Observed;
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
    private final LedgerJournalPostingRepository postings;
    public LedgerJournalService(LedgerJournalPostingRepository postings) { this.postings=postings; }

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

    /**
     * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the ADR-0013
     * D4 execution, the documented recommendation's carrier half): the
     * ORDER-leg courier compensation — the pair the order-leg completion
     * posts in this DEDICATED journal. The pre-fix shape routed every
     * completion into {@link #recognize}, whose first guard throws on the
     * order-leg's by-design {@code paymentIntentId = null} — so the
     * publication failed FOREVER on every order-leg completion (the S12
     * lesson: a legal event with no legal consumer), and the courier's
     * compensation was never booked anywhere.
     *
     * <p>The posting: PSP_CLEARING → DELIVERY_COURIER_PAYABLE for the
     * event's {@code courierCompensationMinor} with the courier's identity
     * as the recipient — the collected money (the order's charge, already
     * in clearing through the ORDER intent the caller resolved as the
     * OFFICIAL payment reference — the recommendation's event binding)
     * funds the courier's payable. The recognize flow stays untouched for
     * DIRECT deliveries (their own capture → advances → revenue chain);
     * the order-leg's chain lives in the generic provider ledger (the
     * D4 split: the seller is credited the goods value NET of the fee, the
     * platform keeps the fee, this pair books what the fee funds).</p>
     */
    @Observed(name="ledger.delivery.carrier.compensate")
    public void compensateCarrier(DeliveryCompletedEvent event, UUID officialPaymentIntentId) {
        if (event.deliveryRequestId()==null || officialPaymentIntentId==null) {
            throw new IllegalArgumentException("Delivery/payment ids are required");
        }
        if (event.courierCompensationMinor()<0
                || (event.courierCompensationMinor()>0
                    && event.courierCompensationMinor()>=event.amountMinor())) {
            // The recognize twin's own guard shape: negative compensation is
            // a defect (never a no-op), and a compensation must stay below
            // the fare it rides.
            throw new IllegalArgumentException("Courier compensation must be nonnegative and below the fare");
        }
        if (event.courierCompensationMinor()>0 && event.courierUserId()==null) {
            throw new IllegalArgumentException("Courier identity is required for a courier payable");
        }
        if (event.courierCompensationMinor()<=0) {
            // The fromCheckout construction guarantees a positive compensation
            // (the fare-covers-compensation placement guard), but a degenerate
            // zero is the honest no-op the recognize flow itself carries for
            // its courier-payable child.
            return;
        }
        validateMoney(event.courierCompensationMinor(), event.currency());
        addIfAbsent(event.deliveryRequestId(), officialPaymentIntentId, "CARRIER_COMPENSATION",
                CLEARING, COURIER_PAYABLE, event.courierCompensationMinor(), event.currency(), null,
                event.courierUserId(),
                "Order-leg courier compensation funded by the collected delivery fee");
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
