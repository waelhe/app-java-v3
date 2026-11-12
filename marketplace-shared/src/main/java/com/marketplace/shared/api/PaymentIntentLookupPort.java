package com.marketplace.shared.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentLookupPort {

    Optional<PaymentIntentDetails> findById(UUID paymentIntentId);

    /**
     * The V229 reconciliation's batch read (CodeRabbit round 1 on #630,
     * verified real — the per-leg findById N+1 over a historical window):
     * resolves the whole window's quote-borne intents in ONE repository
     * round trip. Absent ids are simply missing from the result — the
     * caller's own absent-intent semantics apply per leg.
     */
    List<PaymentIntentDetails> findAllById(Collection<UUID> paymentIntentIds);

    /**
     * Agent findings 2026-10-26T13:11 export, row 41: the checkout
     * delivery leg's PAYMENT PROOF. A dispatch-ready platform-delivery
     * leg requires verified payment for the order's frozen payable — a
     * seller's bare confirmation never proves the money moved. The
     * answer is the payments module's own state vocabulary decision:
     * the settled family (SUCCEEDED, or the refunded family that
     * implies the money landed at least once) — exactly the family the
     * order auto-refund edge treats as collected. Absent intent or any
     * unsettled state answers false: the honest "not yet verified",
     * never an exception (the FeatureGatePort's absent-row law).
     */

    /**
     * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the
     * documented recommendation's event binding): the ORDER's official
     * payment intent — the one intent {@code OrderPaymentPort} created
     * (or idempotently returned) for the order's frozen payable. The
     * order-leg delivery completions carry {@code paymentIntentId = null}
     * by design (their money rides the ORDER's intent, not a
     * delivery-owned one), and the ledger's delivery journal binds its
     * postings to the OFFICIAL intent through this read («ربط
     * DeliveryCompletedEvent بمعرّف نية الدفع الرسمي» — the
     * recommendation's own wording).
     *
     * @param orderId the order whose intent is resolved
     * @return the order's intent details; empty when the order carries no
     *         intent (the honest miss — the caller's own fail-closed
     *         semantics apply)
     */
    Optional<PaymentIntentDetails> findCurrentByOrderId(UUID orderId);
}
