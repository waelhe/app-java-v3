package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The direct-delivery vertical slice (PR #604): the shared contracts the
 * delivery domain speaks across module boundaries answer for their value
 * semantics here. The pins that matter: the payment-intent read model keeps
 * the origin vocabulary closed and testable per origin (the ledger's
 * settlement listeners branch on exactly one predicate each — fail-closed
 * per #606), the legacy ten-argument payment-intent constructor defaults
 * the delivery pairing to "no delivery link, nothing refunded" so pre-W6
 * callers stay source-compatible, and the refund request carries the
 * failure reason with the optional commerce order link (present only when
 * the failed delivery rode an order).
 */
class DeliveryContractsTest {

    @Test
    void orderDeliveryDetails_carriesTheConfirmedOrderSnapshot() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        OrderDeliveryDetails details = new OrderDeliveryDetails(
                orderId, buyerId, sellerId, "SAR", null);
        assertThat(details.orderId()).isEqualTo(orderId);
        assertThat(details.buyerId()).isEqualTo(buyerId);
        assertThat(details.sellerId()).isEqualTo(sellerId);
        assertThat(details.currency()).isEqualTo("SAR");
        assertThat(details.activeDeliveryRequestId()).isNull();
    }

    @Test
    void orderDeliveryDetails_carriesTheExistingDeliveryLink() {
        UUID orderId = UUID.randomUUID();
        UUID activeDeliveryId = UUID.randomUUID();
        OrderDeliveryDetails details = new OrderDeliveryDetails(
                orderId, UUID.randomUUID(), UUID.randomUUID(), "SAR", activeDeliveryId);

        assertThat(details.orderId()).isEqualTo(orderId);
        assertThat(details.activeDeliveryRequestId()).isEqualTo(activeDeliveryId);

        OrderDeliveryDetails same = new OrderDeliveryDetails(
                details.orderId(), details.buyerId(), details.sellerId(),
                details.currency(), details.activeDeliveryRequestId());
        assertThat(details).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(details.toString()).contains(orderId.toString()).contains("SAR");
    }

    @Test
    void orderDeliveryDetails_recordSemantics_hashEqualsAndToString() {
        UUID orderId = UUID.randomUUID();
        OrderDeliveryDetails a = new OrderDeliveryDetails(
                orderId, UUID.randomUUID(), UUID.randomUUID(), "SAR", null);
        OrderDeliveryDetails b = new OrderDeliveryDetails(
                a.orderId(), a.buyerId(), a.sellerId(), a.currency(), a.activeDeliveryRequestId());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains(orderId.toString()).contains("SAR");
    }

    @Test
    void providerFulfillmentLocation_carriesTheVerifiedPickupPoint() {
        UUID providerUserId = UUID.randomUUID();
        ProviderFulfillmentLocation location = new ProviderFulfillmentLocation(
                providerUserId, "مستودع البائع", 37.0, -122.0);
        assertThat(location.providerUserId()).isEqualTo(providerUserId);
        assertThat(location.address()).isEqualTo("مستودع البائع");
        assertThat(location.latitude()).isEqualTo(37.0);
        assertThat(location.longitude()).isEqualTo(-122.0);
    }

    @Test
    void providerFulfillmentLocation_recordSemantics() {
        UUID providerUserId = UUID.randomUUID();
        ProviderFulfillmentLocation a = new ProviderFulfillmentLocation(
                providerUserId, "depot", 1.0, 2.0);
        ProviderFulfillmentLocation b = new ProviderFulfillmentLocation(
                a.providerUserId(), a.address(), a.latitude(), a.longitude());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("depot");
    }

    @Test
    void deliveryRefundRequestedEvent_carriesTheReasonAndTheOptionalOrderLink() {
        UUID deliveryRequestId = UUID.randomUUID();
        DeliveryRefundRequestedEvent event = new DeliveryRefundRequestedEvent(
                deliveryRequestId, "COURIER_UNAVAILABLE", null);
        assertThat(event.deliveryRequestId()).isEqualTo(deliveryRequestId);
        assertThat(event.reason()).isEqualTo("COURIER_UNAVAILABLE");
        assertThat(event.orderId()).isNull();
    }

    @Test
    void deliveryRefundRequestedEvent_legacyConstructorDefaultsTheOrderLinkToNull() {
        UUID deliveryRequestId = UUID.randomUUID();
        DeliveryRefundRequestedEvent legacy = new DeliveryRefundRequestedEvent(
                deliveryRequestId, "COURIER_UNAVAILABLE");
        DeliveryRefundRequestedEvent canonical = new DeliveryRefundRequestedEvent(
                deliveryRequestId, "COURIER_UNAVAILABLE", null);
        assertThat(legacy).isEqualTo(canonical).hasSameHashCodeAs(canonical);
        assertThat(legacy.orderId()).isNull();
    }

    @Test
    void deliveryRefundRequestedEvent_orderLinkedDeliveryCarriesTheOrderId() {
        UUID deliveryRequestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryRefundRequestedEvent a = new DeliveryRefundRequestedEvent(
                deliveryRequestId, "COURIER_UNAVAILABLE", orderId);
        DeliveryRefundRequestedEvent b = new DeliveryRefundRequestedEvent(
                a.deliveryRequestId(), a.reason(), a.orderId());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.orderId()).isEqualTo(orderId);
        assertThat(a.toString()).contains("COURIER_UNAVAILABLE");
    }

    @Test
    void paymentIntentDetails_originPredicatesAnswerForExactlyOneOrigin() {
        UUID id = UUID.randomUUID();
        PaymentIntentDetails booking = intent(id, PaymentIntentDetails.ORIGIN_BOOKING);
        PaymentIntentDetails ad = intent(id, PaymentIntentDetails.ORIGIN_AD);
        PaymentIntentDetails order = intent(id, PaymentIntentDetails.ORIGIN_ORDER);
        PaymentIntentDetails loan = intent(id, PaymentIntentDetails.ORIGIN_LOAN);
        PaymentIntentDetails delivery = intent(id, PaymentIntentDetails.ORIGIN_DELIVERY);

        assertThat(booking.isBookingOrigin()).isTrue();
        assertThat(booking.isAdOrigin()).isFalse();
        assertThat(booking.isOrderOrigin()).isFalse();
        assertThat(booking.isLoanOrigin()).isFalse();
        assertThat(booking.isDeliveryOrigin()).isFalse();

        assertThat(ad.isAdOrigin()).isTrue();
        assertThat(order.isOrderOrigin()).isTrue();
        assertThat(loan.isLoanOrigin()).isTrue();

        assertThat(delivery.isDeliveryOrigin()).isTrue();
        assertThat(delivery.isBookingOrigin()).isFalse();
        assertThat(delivery.isAdOrigin()).isFalse();
        assertThat(delivery.isOrderOrigin()).isFalse();
        assertThat(delivery.isLoanOrigin()).isFalse();
    }

    @Test
    void paymentIntentDetails_legacyConstructorDefaultsTheDeliveryPairing() {
        UUID paymentIntentId = UUID.randomUUID();
        PaymentIntentDetails legacy = new PaymentIntentDetails(
                paymentIntentId, UUID.randomUUID(), UUID.randomUUID(), null,
                "CAPTURED", PaymentIntentDetails.ORIGIN_BOOKING, 1000, "SAR",
                null, null);
        assertThat(legacy.deliveryRequestId()).isNull();
        assertThat(legacy.refundedAmountCents()).isZero();
        assertThat(legacy.isBookingOrigin()).isTrue();
    }

    @Test
    void paymentIntentDetails_canonicalConstructorCarriesTheDeliveryPairingAndRefunds() {
        UUID paymentIntentId = UUID.randomUUID();
        UUID deliveryRequestId = UUID.randomUUID();
        PaymentIntentDetails a = new PaymentIntentDetails(
                paymentIntentId, null, UUID.randomUUID(), null,
                "PARTIALLY_REFUNDED", PaymentIntentDetails.ORIGIN_DELIVERY, 1000, "SAR",
                null, null, null, deliveryRequestId, 250);
        PaymentIntentDetails b = new PaymentIntentDetails(
                a.paymentIntentId(), a.bookingId(), a.consumerId(), a.adCampaignId(),
                a.status(), a.origin(), a.amountCents(), a.currency(),
                a.orderId(), a.loanId(), a.commissionRate(), a.deliveryRequestId(), a.refundedAmountCents());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.deliveryRequestId()).isEqualTo(deliveryRequestId);
        assertThat(a.refundedAmountCents()).isEqualTo(250);
        assertThat(a.isDeliveryOrigin()).isTrue();
        assertThat(a.toString()).contains("PARTIALLY_REFUNDED");
    }

    private PaymentIntentDetails intent(UUID paymentIntentId, String origin) {
        return new PaymentIntentDetails(
                paymentIntentId, null, UUID.randomUUID(), null,
                "CAPTURED", origin, 1000, "SAR", null, null, null, null, 0);
    }
}
