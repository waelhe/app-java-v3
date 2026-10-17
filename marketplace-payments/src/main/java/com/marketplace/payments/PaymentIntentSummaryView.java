package com.marketplace.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Closed projection for payment-intent listing queries.
 * Loads only the fields needed for summary display.
 *
 * <p><b>Widened by the payments plan's Phase-1 unified finance-operator
 * read model:</b> the ORIGIN and its subject columns (the
 * origin-agnostic single search — an ORDER/LOAN/AD row must answer the
 * same summary shape a BOOKING row does), the ADR-0013 frozen commission
 * rate (D2 — {@code null} only for pre-V220 legacy rows), and the PSP
 * linkage. Interface projection on a derived query: the widened getters
 * widen the generated select; no hand-written SQL exists to drift.
 */
public interface PaymentIntentSummaryView {

    UUID getId();

    UUID getBookingId();

    UUID getAdCampaignId();

    UUID getOrderId();

    UUID getLoanId();

    UUID getConsumerId();

    Long getAmountCents();

    String getCurrency();

    String getOrigin();

    PaymentIntentStatus getStatus();

    Long getRefundedAmountCents();

    BigDecimal getCommissionRate();

    String getPspIntentId();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
