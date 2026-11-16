package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout fulfillment seam's shared contracts (the payments-and-
 * delivery architecture §3.2 Mode B slice): the fare option the checkout
 * shows and the placement freezes, and the frozen selection the delivery
 * module reads after confirmation — both answer for their value semantics
 * here, in the {@code DeliveryContractsTest} family's own style.
 */
class CheckoutFulfillmentContractsTest {

    @Test
    void deliveryFareOption_carriesTheServicedFareFacts() {
        CheckoutDeliveryPort.DeliveryFareOption option =
                new CheckoutDeliveryPort.DeliveryFareOption(true, 1500L, 3600L, "SAR", null);

        assertThat(option.serviceable()).isTrue();
        assertThat(option.feeMinor()).isEqualTo(1500L);
        assertThat(option.etaSeconds()).isEqualTo(3600L);
        assertThat(option.currency()).isEqualTo("SAR");
        assertThat(option.refusal()).isNull();
    }

    @Test
    void deliveryFareOption_unserviceableIsTheHonestZeroFareWithItsReason() {
        // never an exception: the checkout shows «no platform delivery for
        // this seller», the buyer picks pickup or removes the lines
        CheckoutDeliveryPort.DeliveryFareOption option =
                CheckoutDeliveryPort.DeliveryFareOption.unserviceable("route not served");

        assertThat(option.serviceable()).isFalse();
        assertThat(option.feeMinor()).isZero();
        assertThat(option.etaSeconds()).isZero();
        assertThat(option.currency()).isNull();
        assertThat(option.refusal()).isEqualTo("route not served");
    }

    @Test
    void deliveryFareOption_recordSemantics_hashEqualsAndToString() {
        CheckoutDeliveryPort.DeliveryFareOption a =
                new CheckoutDeliveryPort.DeliveryFareOption(true, 1500L, 3600L, "SAR", null);
        CheckoutDeliveryPort.DeliveryFareOption same =
                new CheckoutDeliveryPort.DeliveryFareOption(true, 1500L, 3600L, "SAR", null);

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a.toString()).contains("SAR").contains("1500");
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals(new Object())).isFalse();
    }

    @Test
    void orderFulfillmentSelection_carriesTheFrozenAgreement() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID leg = UUID.randomUUID();
        OrderFulfillmentSelection selection = new OrderFulfillmentSelection(
                orderId, buyerId, sellerId, "SAR", "PLATFORM_DELIVERY",
                "1 King Fahd Rd, Riyadh", 24.7136, 46.6753, 1500L, leg);

        assertThat(selection.orderId()).isEqualTo(orderId);
        assertThat(selection.buyerId()).isEqualTo(buyerId);
        assertThat(selection.sellerId()).isEqualTo(sellerId);
        assertThat(selection.currency()).isEqualTo("SAR");
        assertThat(selection.fulfillmentMethod()).isEqualTo("PLATFORM_DELIVERY");
        assertThat(selection.shipToAddress()).isNotBlank();
        assertThat(selection.shipToLatitude()).isEqualTo(24.7136);
        assertThat(selection.shipToLongitude()).isEqualTo(46.6753);
        assertThat(selection.shippingAmountMinor()).isEqualTo(1500L);
        assertThat(selection.activeDeliveryRequestId()).isEqualTo(leg);
        assertThat(selection.platformDelivery()).isTrue();
    }

    @Test
    void orderFulfillmentSelection_pickupIsNotPlatformDelivery() {
        OrderFulfillmentSelection pickup = new OrderFulfillmentSelection(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SAR", "PICKUP",
                null, null, null, 0L, null);

        assertThat(pickup.platformDelivery()).isFalse();
        assertThat(pickup.shipToAddress()).isNull();
        assertThat(pickup.shippingAmountMinor()).isZero();
    }

    @Test
    void orderFulfillmentSelection_theLegacyNullMethodStaysTheManualAddOnPath() {
        // NULL method = the pre-V224 legacy agreement (or a method-less
        // order): the #604 manual add-on delivery path stays available for
        // exactly these — never platform delivery
        OrderFulfillmentSelection legacy = new OrderFulfillmentSelection(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SAR", null,
                null, null, null, 0L, null);

        assertThat(legacy.platformDelivery()).isFalse();
        assertThat(legacy.fulfillmentMethod()).isNull();
    }

    @Test
    void orderFulfillmentSelection_recordSemantics_hashEqualsAndToString() {
        UUID orderId = UUID.randomUUID();
        OrderFulfillmentSelection a = new OrderFulfillmentSelection(
                orderId, UUID.randomUUID(), UUID.randomUUID(), "SAR", "PICKUP",
                null, null, null, 0L, null);
        OrderFulfillmentSelection same = new OrderFulfillmentSelection(
                a.orderId(), a.buyerId(), a.sellerId(), a.currency(), a.fulfillmentMethod(),
                a.shipToAddress(), a.shipToLatitude(), a.shipToLongitude(),
                a.shippingAmountMinor(), a.activeDeliveryRequestId());

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a.toString()).contains(orderId.toString()).contains("PICKUP");
    }
}
