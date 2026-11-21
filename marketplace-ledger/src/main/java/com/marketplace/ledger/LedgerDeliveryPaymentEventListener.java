package com.marketplace.ledger;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the ADR-0013
 * D4 execution, the documented recommendation's event binding): the
 * delivery-completion routing the pre-fix shape lacked. The event family
 * has TWO legal shapes and the pre-fix listener gave them one consumer:
 *
 * <ul>
 *   <li><b>Direct deliveries</b> (paymentIntentId \u2260 null): the
 *   delivery-owned intent's money — the capture \u2192 recognize chain runs
 *   exactly as before ({@link LedgerJournalService#recognize}).</li>
 *   <li><b>Order-leg deliveries</b> (paymentIntentId = null BY DESIGN,
 *   orderId \u2260 null): the money rides the ORDER's intent (the ONE intent
 *   the checkout's platform delivery froze the fee inside); the pre-fix
 *   listener routed these into {@code recognize} whose first guard
 *   throws on the null intent id — the publication failed FOREVER on
 *   every order-leg completion (the S12 lesson: a legal event with no
 *   legal consumer must not fail forever in the publication registry),
 *   and the courier's compensation was never booked anywhere (the
 *   ledger's own finding A1).</li>
 * </ul>
 *
 * <p>The order-leg consumer: resolve the order's OFFICIAL intent through
 * {@link PaymentIntentLookupPort#findCurrentByOrderId(UUID)} («ربط
 * DeliveryCompletedEvent بمعرّف نية الدفع الرسمي» — the recommendation's
 * own wording) and book the courier's compensation pair in the dedicated
 * journal ({@link LedgerJournalService#compensateCarrier}). An order-leg
 * completion whose order carries no intent is a REAL inconsistency (a
 * delivered-but-unpaid order — the dispatch gate requires settled
 * payment), so the fail-closed refusal stands THERE: the explicit
 * exception, the recoverable publication, monitoring sees it.</p>
 */
@Component
public class LedgerDeliveryPaymentEventListener {

    private final LedgerJournalService journal;
    private final PaymentIntentLookupPort lookup;

    public LedgerDeliveryPaymentEventListener(LedgerJournalService j, PaymentIntentLookupPort l) {
        journal = j;
        lookup = l;
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent e) {
        if (!"COMPLETED".equals(e.state()) && !"REFUNDED".equals(e.state())
                && !"PARTIALLY_REFUNDED".equals(e.state())) {
            return;
        }
        PaymentIntentDetails p = lookup.findById(e.paymentIntentId())
                .orElseThrow(() -> new IllegalStateException("Payment event references an unknown intent"));
        if (!p.isDeliveryOrigin()) {
            return;
        }
        if ("COMPLETED".equals(e.state())) {
            if (!"SUCCEEDED".equals(p.status())) {
                throw new IllegalStateException("Completion event is not backed by successful delivery payment");
            }
            journal.capture(p);
        } else {
            if (!"REFUNDED".equals(p.status()) && !"PARTIALLY_REFUNDED".equals(p.status())) {
                throw new IllegalStateException("Refund event is not backed by refund state");
            }
            journal.refund(p);
        }
    }

    @ApplicationModuleListener
    public void onDeliveryCompleted(DeliveryCompletedEvent e) {
        if (e.paymentIntentId() == null && e.orderId() != null) {
            // A1: the order-leg — bind the completion to the ORDER's official
            // intent and book the courier's compensation in the dedicated
            // journal (never the recognize chain, whose delivery-owned capture
            // the order-leg never had).
            PaymentIntentDetails orderIntent = lookup.findCurrentByOrderId(e.orderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Order-leg delivery completion references an order with no payment intent: "
                                    + e.orderId() + " (delivery " + e.deliveryRequestId()
                                    + ") — the dispatch gate requires settled payment; no carrier "
                                    + "compensation was booked"));
            journal.compensateCarrier(e, orderIntent.paymentIntentId());
            return;
        }
        journal.recognize(e);
    }
}
