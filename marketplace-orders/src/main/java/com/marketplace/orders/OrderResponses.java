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
            @Schema(description = "ADR-0012: the purchasable variant the line carries — null for the product-level line") UUID variantId,
            @Schema(description = "Quantity of the purchasable unit in the cart") int quantity,
            @Schema(description = "The per-unit amount in minor units (cents) as the buyer currently sees it") long unitAmountMinor,
            @Schema(description = "ISO-4217 currency code") String currency,
            @Schema(description = "The line's total in minor units") long lineTotalMinor) {

        static CartItemResponse of(CartItem item) {
            return new CartItemResponse(item.getId(), item.getProductId(), item.getVariantId(),
                    item.getQuantity(), item.getUnitAmountMinor(), item.getCurrency(), item.lineTotalMinor());
        }
    }

    public record OrderItemResponse(
            @Schema(description = "The order line's id") UUID id,
            @Schema(description = "The store product the line froze at placement time") UUID productId,
            @Schema(description = "ADR-0012: the variant the line froze at placement — null for the product-level line") UUID variantId,
            @Schema(description = "Quantity frozen at placement") int quantity,
            @Schema(description = "The per-unit amount frozen at placement (minor units)") long unitAmountMinor,
            @Schema(description = "ISO-4217 currency code frozen at placement") String currency,
            @Schema(description = "The line's total in minor units") long lineTotalMinor) {

        static OrderItemResponse of(OrderItem item) {
            return new OrderItemResponse(item.getId(), item.getProductId(), item.getVariantId(),
                    item.getQuantity(), item.getUnitAmountMinor(), item.getCurrency(), item.lineTotalMinor());
        }
    }

    public record OrderResponse(
            @Schema(description = "The order's id") UUID id,
            @Schema(description = "The machine state: PLACED → CONFIRMED → FULFILLED, with CANCELLED from the open states") OrderStatus status,
            @Schema(description = "The order's frozen lines") List<OrderItemResponse> items,
            @Schema(description = "The derived total in minor units (never caller-supplied)") long totalAmountMinor,
            @Schema(description = "The Phase-3 frozen subtotal — the re-priced lines BEFORE the commercial rules (null for pre-V223 orders: their total is the whole recorded agreement)") Long subtotalAmountMinor,
            @Schema(description = "The Phase-3 frozen promotion + voucher discount (null for pre-V223 orders)") Long discountAmountMinor,
            @Schema(description = "The Phase-3 frozen tax component (null for pre-V223 orders)") Long taxAmountMinor,
            @Schema(description = "ISO-4217 currency code") String currency,
            @Schema(description = "When the order was placed") Instant placedAt,
            @Schema(description = "When the merchant confirmed it (null while PLACED)") Instant confirmedAt,
            @Schema(description = "When fulfillment completed (null until FULFILLED)") Instant fulfilledAt,
            @Schema(description = "When it was cancelled (null unless CANCELLED)") Instant cancelledAt,
            @Schema(description = "The cancellation reason (null unless CANCELLED)") String cancelReason,
            @Schema(description = "Linked marketplace delivery request, when shipping was requested") UUID deliveryRequestId,
            @Schema(description = "ADR-0010: the other orders this placement created when the cart mixed "
                    + "sellers (one order per seller) — empty for the single-seller placement and "
                    + "for every other read") List<OrderResponse> additionalOrders) {

        static OrderResponse of(OrderDetail detail) {
            return of(detail.order(), detail.items());
        }

        static OrderResponse of(Order order, List<OrderItem> items) {
            return new OrderResponse(order.getId(), order.getStatus(),
                    items.stream().map(OrderItemResponse::of).toList(),
                    order.getTotalAmountMinor(),
                    order.getSubtotalAmountMinor(), order.getDiscountAmountMinor(),
                    order.getTaxAmountMinor(),
                    order.getCurrency(),
                    order.getPlacedAt(), order.getConfirmedAt(), order.getFulfilledAt(),
                    order.getCancelledAt(), order.getCancelReason(), order.getDeliveryRequestId(), List.of());
        }

        /**
         * ADR-0010: the placement's view — the first group's order is the
         * response body; its sibling orders ride the ADDITIVE
         * {@code additionalOrders} list (no field removed, no client
         * broken — the OpenAPI compatibility gate's additive-only rule).
         */
        static OrderResponse ofPlacement(List<OrderDetail> placed) {
            OrderResponse primary = of(placed.get(0));
            List<OrderResponse> additional = placed.stream().skip(1).map(OrderResponse::of).toList();
            return new OrderResponse(primary.id(), primary.status(), primary.items(),
                    primary.totalAmountMinor(), primary.subtotalAmountMinor(),
                    primary.discountAmountMinor(), primary.taxAmountMinor(), primary.currency(),
                    primary.placedAt(), primary.confirmedAt(), primary.fulfilledAt(),
                    primary.cancelledAt(), primary.cancelReason(), primary.deliveryRequestId(), additional);
        }
    }

    /**
     * The Phase-3 checkout preview — one seller group's breakdown exactly
     * as the placement would freeze it (the service's own
     * {@code PlacementPreview} projected for the wire).
     */
    public record PlacementPreviewResponse(
            @Schema(description = "The seller group this breakdown belongs to") UUID sellerId,
            @Schema(description = "The re-priced subtotal (minor units)") long subtotalMinor,
            @Schema(description = "The best single applicable promotion's discount (0 when none applies)") long promotionDiscountMinor,
            @Schema(description = "The promotion's name (null when none applies)") String promotionName,
            @Schema(description = "The voucher's discount (0 when no code was supplied)") long voucherDiscountMinor,
            @Schema(description = "The voucher's normalized code (null when none)") String voucherCode,
            @Schema(description = "The jurisdiction's tax component") long taxMinor,
            @Schema(description = "The tax policy's country (null when no policy is configured)") String taxCountry,
            @Schema(description = "Whether the tax is INCLUSIVE (contained in the agreed prices)") boolean taxInclusive,
            @Schema(description = "The tax policy's rate in basis points") int taxRateBps,
            @Schema(description = "The buyer's would-be agreement: subtotal − discounts + (tax when EXCLUSIVE)") long totalMinor,
            @Schema(description = "ISO-4217 currency code") String currency) {

        static PlacementPreviewResponse of(OrdersService.PlacementPreview preview) {
            var b = preview.breakdown();
            return new PlacementPreviewResponse(preview.sellerId(), b.subtotalMinor(),
                    b.promotionDiscountMinor(), b.promotionName(), b.voucherDiscountMinor(),
                    b.voucherCode(), b.taxMinor(), b.taxCountry(), b.taxInclusive(),
                    b.taxRateBps(), b.totalMinor(), preview.currency());
        }
    }
}
