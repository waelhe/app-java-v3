package com.marketplace.orders;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A-11 — the read models the controllers answer with. Plain records (no
 * MapStruct layer: the mapping is a field-for-field copy the records state
 * in place, one less generated indirection for the module's two read
 * shapes).
 */
public final class OrderResponses {

    private OrderResponses() {
    }

    public record CartItemResponse(
            @Schema(description = "The cart line's id") UUID id,
            @Schema(description = "The store product the line refers to (opaque reference — the product root arrives with M1/A-17)") UUID productId,
            @Schema(description = "Quantity of the product in the cart") int quantity,
            @Schema(description = "The per-unit amount in minor units (cents) as the buyer currently sees it") long unitAmountMinor,
            @Schema(description = "ISO-4217 currency code") String currency,
            @Schema(description = "The line's total in minor units") long lineTotalMinor) {

        static CartItemResponse of(CartItem item) {
            return new CartItemResponse(item.getId(), item.getProductId(), item.getQuantity(),
                    item.getUnitAmountMinor(), item.getCurrency(), item.lineTotalMinor());
        }
    }

    public record OrderItemResponse(
            @Schema(description = "The order line's id") UUID id,
            @Schema(description = "The store product the line froze at placement time") UUID productId,
            @Schema(description = "Quantity frozen at placement") int quantity,
            @Schema(description = "The per-unit amount frozen at placement (minor units)") long unitAmountMinor,
            @Schema(description = "ISO-4217 currency code frozen at placement") String currency,
            @Schema(description = "The line's total in minor units") long lineTotalMinor) {

        static OrderItemResponse of(OrderItem item) {
            return new OrderItemResponse(item.getId(), item.getProductId(), item.getQuantity(),
                    item.getUnitAmountMinor(), item.getCurrency(), item.lineTotalMinor());
        }
    }

    public record OrderResponse(
            @Schema(description = "The order's id") UUID id,
            @Schema(description = "The machine state: PLACED → CONFIRMED → FULFILLED, with CANCELLED from the open states") OrderStatus status,
            @Schema(description = "The order's frozen lines") List<OrderItemResponse> items,
            @Schema(description = "The derived total in minor units (sum of the frozen lines — never caller-supplied)") long totalAmountMinor,
            @Schema(description = "ISO-4217 currency code") String currency,
            @Schema(description = "When the order was placed") Instant placedAt,
            @Schema(description = "When the merchant confirmed it (null while PLACED)") Instant confirmedAt,
            @Schema(description = "When fulfillment completed (null until FULFILLED)") Instant fulfilledAt,
            @Schema(description = "When it was cancelled (null unless CANCELLED)") Instant cancelledAt,
            @Schema(description = "The cancellation reason (null unless CANCELLED)") String cancelReason) {

        static OrderResponse of(OrderDetail detail) {
            return of(detail.order(), detail.items());
        }

        static OrderResponse of(Order order, List<OrderItem> items) {
            return new OrderResponse(order.getId(), order.getStatus(),
                    items.stream().map(OrderItemResponse::of).toList(),
                    order.getTotalAmountMinor(), order.getCurrency(),
                    order.getPlacedAt(), order.getConfirmedAt(), order.getFulfilledAt(),
                    order.getCancelledAt(), order.getCancelReason());
        }
    }
}
