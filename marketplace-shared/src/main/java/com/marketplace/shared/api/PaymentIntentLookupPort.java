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
    boolean isOrderPayableSettled(UUID orderId);
}
