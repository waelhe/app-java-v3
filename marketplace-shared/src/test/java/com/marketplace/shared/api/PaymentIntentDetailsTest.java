package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The {@code PaymentIntentDetails} record's own settlement vocabulary —
 * every origin probe (the exhaustive dispatch the ledger's source audit
 * fail-closed finding demands: BOOKING/AD/ORDER/LOAN/DELIVERY are five
 * NAMED branches, never a silent fallback), the convenience construction
 * forms (the pre-delivery ten-argument fixture shape and the
 * commission-freeze canonical form), and the origin constants the
 * settlement branches read.
 */
class PaymentIntentDetailsTest {

    private final UUID intentId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID consumerId = UUID.randomUUID();
    private final UUID adCampaignId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();

    @Test
    void theOriginProbesAnswerTheirOwnNamedBranches() {
        assertThat(intent(PaymentIntentDetails.ORIGIN_BOOKING).isBookingOrigin()).isTrue();
        assertThat(intent(PaymentIntentDetails.ORIGIN_BOOKING).isAdOrigin()).isFalse();
        assertThat(intent(PaymentIntentDetails.ORIGIN_BOOKING).isOrderOrigin()).isFalse();
        assertThat(intent(PaymentIntentDetails.ORIGIN_BOOKING).isLoanOrigin()).isFalse();
        assertThat(intent(PaymentIntentDetails.ORIGIN_BOOKING).isDeliveryOrigin()).isFalse();

        assertThat(intent(PaymentIntentDetails.ORIGIN_AD).isAdOrigin()).isTrue();
        assertThat(intent(PaymentIntentDetails.ORIGIN_AD).isBookingOrigin()).isFalse();

        assertThat(intent(PaymentIntentDetails.ORIGIN_ORDER).isOrderOrigin()).isTrue();
        assertThat(intent(PaymentIntentDetails.ORIGIN_ORDER).isAdOrigin()).isFalse();

        assertThat(intent(PaymentIntentDetails.ORIGIN_LOAN).isLoanOrigin()).isTrue();
        assertThat(intent(PaymentIntentDetails.ORIGIN_LOAN).isOrderOrigin()).isFalse();

        assertThat(intent(PaymentIntentDetails.ORIGIN_DELIVERY).isDeliveryOrigin()).isTrue();
        assertThat(intent(PaymentIntentDetails.ORIGIN_DELIVERY).isLoanOrigin()).isFalse();
    }

    @Test
    void anUnknownOriginAnswersFalseOnEveryProbe() {
        // The exhaustive dispatch's own negative: an unknown origin falls
        // through NO branch — every probe answers false (the fail-closed
        // stance the ledger's dispatch builds on).
        PaymentIntentDetails unknown = intent("SOMETHING_ELSE");
        assertThat(unknown.isBookingOrigin()).isFalse();
        assertThat(unknown.isAdOrigin()).isFalse();
        assertThat(unknown.isOrderOrigin()).isFalse();
        assertThat(unknown.isLoanOrigin()).isFalse();
        assertThat(unknown.isDeliveryOrigin()).isFalse();
    }

    @Test
    void thePreDeliveryFixtureShapeCarriesTheHonestZeros() {
        PaymentIntentDetails fixture = new PaymentIntentDetails(intentId, bookingId, consumerId,
                adCampaignId, "CAPTURED", PaymentIntentDetails.ORIGIN_BOOKING, 2500L, "SAR", null, null);
        assertThat(fixture.paymentIntentId()).isEqualTo(intentId);
        assertThat(fixture.bookingId()).isEqualTo(bookingId);
        assertThat(fixture.consumerId()).isEqualTo(consumerId);
        assertThat(fixture.adCampaignId()).isEqualTo(adCampaignId);
        assertThat(fixture.status()).isEqualTo("CAPTURED");
        assertThat(fixture.origin()).isEqualTo(PaymentIntentDetails.ORIGIN_BOOKING);
        assertThat(fixture.amountCents()).isEqualTo(2500L);
        assertThat(fixture.currency()).isEqualTo("SAR");
        assertThat(fixture.orderId()).isNull();
        assertThat(fixture.loanId()).isNull();
        assertThat(fixture.commissionRate()).isNull();
        assertThat(fixture.deliveryRequestId()).isNull();
        assertThat(fixture.refundedAmountCents()).isZero();
    }

    @Test
    void theCommissionFreezeCanonicalFormRidesTheFrozenRate() {
        PaymentIntentDetails frozen = new PaymentIntentDetails(intentId, bookingId, consumerId,
                adCampaignId, "CAPTURED", PaymentIntentDetails.ORIGIN_BOOKING, 2500L, "SAR", null, null,
                new BigDecimal("0.125"));
        assertThat(frozen.commissionRate()).isEqualByComparingTo(new BigDecimal("0.125"));
        assertThat(frozen.deliveryRequestId()).isNull();
        assertThat(frozen.refundedAmountCents()).isZero();
    }

    @Test
    void theFullFormCarriesEveryWidenedField() {
        UUID deliveryRequestId = UUID.randomUUID();
        PaymentIntentDetails delivery = new PaymentIntentDetails(intentId, null, consumerId, null,
                "PARTIALLY_REFUNDED", PaymentIntentDetails.ORIGIN_DELIVERY, 5000L, "SAR",
                null, null, new BigDecimal("0.05"), deliveryRequestId, 1200L);
        assertThat(delivery.isDeliveryOrigin()).isTrue();
        assertThat(delivery.deliveryRequestId()).isEqualTo(deliveryRequestId);
        assertThat(delivery.refundedAmountCents()).isEqualTo(1200L);
        assertThat(delivery.bookingId()).isNull();
        assertThat(delivery.adCampaignId()).isNull();
    }

    @Test
    void theOrderAndLoanOriginsCarryTheirOwnSubjectIds() {
        PaymentIntentDetails order = new PaymentIntentDetails(intentId, null, consumerId, null,
                "REQUIRES_CAPTURE", PaymentIntentDetails.ORIGIN_ORDER, 3100L, "SAR", orderId, null);
        assertThat(order.isOrderOrigin()).isTrue();
        assertThat(order.orderId()).isEqualTo(orderId);

        PaymentIntentDetails loan = new PaymentIntentDetails(intentId, null, consumerId, null,
                "SUCCEEDED", PaymentIntentDetails.ORIGIN_LOAN, 750L, "SAR", null, loanId);
        assertThat(loan.isLoanOrigin()).isTrue();
        assertThat(loan.loanId()).isEqualTo(loanId);
    }

    private PaymentIntentDetails intent(String origin) {
        return new PaymentIntentDetails(intentId, bookingId, consumerId, adCampaignId,
                "SUCCEEDED", origin, 1000L, "SAR", null, null);
    }
}
