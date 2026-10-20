package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers generated record accessors nested in the public cross-module commerce ports. */
class CommercePortRecordContractTest {

    @Test
    void checkoutCommandAndCartLineExposeEveryContractField() {
        var command = new OrderCommercePort.CheckoutCommand(
                "SAVE10", "PLATFORM_DELIVERY", "10 Main Street", 24.7136, 46.6753);
        assertThat(command.voucherCode()).isEqualTo("SAVE10");
        assertThat(command.fulfillmentMethod()).isEqualTo("PLATFORM_DELIVERY");
        assertThat(command.destinationAddress()).isEqualTo("10 Main Street");
        assertThat(command.destinationLatitude()).isEqualTo(24.7136);
        assertThat(command.destinationLongitude()).isEqualTo(46.6753);

        UUID lineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        var line = new OrderCommercePort.CartLineView(
                lineId, productId, variantId, 3, "1250", "SAR", "3750");
        assertThat(line.id()).isEqualTo(lineId);
        assertThat(line.productId()).isEqualTo(productId);
        assertThat(line.variantId()).isEqualTo(variantId);
        assertThat(line.quantity()).isEqualTo(3);
        assertThat(line.unitAmountMinor()).isEqualTo("1250");
        assertThat(line.currency()).isEqualTo("SAR");
        assertThat(line.lineTotalMinor()).isEqualTo("3750");
    }

    @Test
    void deliveryOptionAndCheckoutPreviewExposeEveryContractField() {
        var delivery = new OrderCommercePort.DeliveryOptionView(true, "500", 3600L, "SAR", null);
        assertThat(delivery.serviceable()).isTrue();
        assertThat(delivery.feeMinor()).isEqualTo("500");
        assertThat(delivery.etaSeconds()).isEqualTo(3600L);
        assertThat(delivery.currency()).isEqualTo("SAR");
        assertThat(delivery.refusal()).isNull();

        UUID sellerId = UUID.randomUUID();
        var preview = new OrderCommercePort.CheckoutPreviewView(
                sellerId, "4000", "400", "Summer promotion", "100", "SAVE10",
                "300", "SA", true, 750, "3800", "SAR", delivery);
        assertThat(preview.sellerId()).isEqualTo(sellerId);
        assertThat(preview.subtotalMinor()).isEqualTo("4000");
        assertThat(preview.promotionDiscountMinor()).isEqualTo("400");
        assertThat(preview.promotionName()).isEqualTo("Summer promotion");
        assertThat(preview.voucherDiscountMinor()).isEqualTo("100");
        assertThat(preview.voucherCode()).isEqualTo("SAVE10");
        assertThat(preview.taxMinor()).isEqualTo("300");
        assertThat(preview.taxCountry()).isEqualTo("SA");
        assertThat(preview.taxInclusive()).isTrue();
        assertThat(preview.taxRateBps()).isEqualTo(750);
        assertThat(preview.totalMinor()).isEqualTo("3800");
        assertThat(preview.currency()).isEqualTo("SAR");
        assertThat(preview.deliveryOption()).isSameAs(delivery);
    }

    @Test
    void orderLineAndOrderViewsExposeEveryFrozenOrderField() {
        UUID lineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        var line = new OrderCommercePort.OrderLineView(
                lineId, productId, variantId, 2, "1500", "SAR", "3000");
        assertThat(line.id()).isEqualTo(lineId);
        assertThat(line.productId()).isEqualTo(productId);
        assertThat(line.variantId()).isEqualTo(variantId);
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.unitAmountMinor()).isEqualTo("1500");
        assertThat(line.currency()).isEqualTo("SAR");
        assertThat(line.lineTotalMinor()).isEqualTo("3000");

        UUID additionalId = UUID.randomUUID();
        var additional = new OrderCommercePort.OrderView(
                additionalId, "PLACED", List.of(), "500", "500", "0", "0", "PICKUP",
                "0", null, "SAR", Instant.parse("2026-10-20T10:00:00Z"),
                null, null, null, null, null, List.of());
        UUID orderId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Instant placed = Instant.parse("2026-10-20T10:00:00Z");
        Instant confirmed = placed.plusSeconds(60);
        Instant fulfilled = confirmed.plusSeconds(60);
        Instant cancelled = fulfilled.plusSeconds(60);
        var order = new OrderCommercePort.OrderView(
                orderId, "CANCELLED", List.of(line), "3000", "3300", "300", "0",
                "PICKUP", "0", "10 Main Street", "SAR", placed, confirmed, fulfilled,
                cancelled, "buyer request", deliveryId, List.of(additional));
        assertThat(order.id()).isEqualTo(orderId);
        assertThat(order.status()).isEqualTo("CANCELLED");
        assertThat(order.items()).containsExactly(line);
        assertThat(order.totalAmountMinor()).isEqualTo("3000");
        assertThat(order.subtotalAmountMinor()).isEqualTo("3300");
        assertThat(order.discountAmountMinor()).isEqualTo("300");
        assertThat(order.taxAmountMinor()).isEqualTo("0");
        assertThat(order.fulfillmentMethod()).isEqualTo("PICKUP");
        assertThat(order.shippingAmountMinor()).isEqualTo("0");
        assertThat(order.shipToAddress()).isEqualTo("10 Main Street");
        assertThat(order.currency()).isEqualTo("SAR");
        assertThat(order.placedAt()).isEqualTo(placed);
        assertThat(order.confirmedAt()).isEqualTo(confirmed);
        assertThat(order.fulfilledAt()).isEqualTo(fulfilled);
        assertThat(order.cancelledAt()).isEqualTo(cancelled);
        assertThat(order.cancelReason()).isEqualTo("buyer request");
        assertThat(order.deliveryRequestId()).isEqualTo(deliveryId);
        assertThat(order.additionalOrders()).containsExactly(additional);
    }

    @Test
    void paymentIntentClientProjectionExposesEveryContractField() {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Instant created = Instant.parse("2026-10-20T10:00:00Z");
        Instant updated = created.plusSeconds(60);
        var intent = new PaymentIntentClientPort.PaymentIntentClientView(
                id, bookingId, "3000", "SAR", "CREATED", "psp-1", "client-secret", created, updated);
        assertThat(intent.id()).isEqualTo(id);
        assertThat(intent.bookingId()).isEqualTo(bookingId);
        assertThat(intent.amountMinor()).isEqualTo("3000");
        assertThat(intent.currency()).isEqualTo("SAR");
        assertThat(intent.status()).isEqualTo("CREATED");
        assertThat(intent.pspIntentId()).isEqualTo("psp-1");
        assertThat(intent.clientSecret()).isEqualTo("client-secret");
        assertThat(intent.createdAt()).isEqualTo(created);
        assertThat(intent.updatedAt()).isEqualTo(updated);
    }
}
