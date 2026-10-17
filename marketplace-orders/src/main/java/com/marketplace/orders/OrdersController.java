package com.marketplace.orders;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — the buyer's order surface: place
 * from the cart, read the machine's state, cancel while the order is still
 * open. The merchant-side transitions (confirm, fulfill) are the machine's
 * service-level machinery — their HTTP surface arrives with the store's
 * merchant console (compliance plan C.7/M1, unit A-17), guarded and
 * event-publishing from day one, exercised by the journey gate.
 */
@RestController
@RequestMapping(value = ApiConstants.ORDERS, version = "1.0")
public class OrdersController {

    private final OrdersService ordersService;
    private final CurrentUserProvider currentUserProvider;

    public OrdersController(OrdersService ordersService, CurrentUserProvider currentUserProvider) {
        this.ordersService = ordersService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Cart to order(S): the placement transaction — the cart IS the
     * order(S)' content (the totals are derived, never caller-supplied);
     * the optional body carries the buyer's VOUCHER CODE (the Phase-3
     * commercial rules — an absent body or null code places exactly as
     * before, byte-compatible).
     * Chained through the service's view assembly: the HTTP boundary never
     * declares an entity local (the controllersMustNotDependOnJpaEntities
     * rule). ADR-0010: the body is the first group's order; a mixed-seller
     * placement's sibling orders ride the ADDITIVE {@code additionalOrders}
     * list (additive-only — no client breaks, no gate entry).
     */
    @PostMapping
    @Operation(summary = "Place an order from the cart", description = "Freezes the caller's "
            + "active cart lines into order(s) (PLACED) — one order per seller (ADR-0010: a "
            + "mixed-seller cart splits; the response body is the first order and its siblings "
            + "ride additionalOrders) — with the Phase-3 commercial breakdown (price-list "
            + "overrides, the best single applicable promotion, at most one voucher on the "
            + "primary order, the jurisdiction's explicit tax) frozen on each order — and "
            + "tombstones the cart, one atomic transaction. An empty or absent cart answers 409.")
    public ResponseEntity<OrderResponses.OrderResponse> place(
            @org.springframework.lang.Nullable @RequestBody(required = false) PlaceOrderRequest request,
            Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        java.util.List<OrderDetail> placed = ordersService.place(caller,
                request == null ? null : request.voucherCode(),
                request == null || request.fulfillment() == null ? null
                        : request.fulfillment().toSelection());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(OrderResponses.OrderResponse.ofPlacement(placed));
    }

    /**
     * The Phase-3 checkout preview: the buyer's active cart re-priced and
     * broken down EXACTLY as the placement would freeze it — one breakdown
     * per seller group (the primary group carries the voucher). Read-only:
     * no reservation, no redemption, no tombstone; the placement re-computes
     * at ITS instant.
     */
    @PostMapping("/cart/preview")
    @Operation(summary = "Preview the placement's commercial breakdown", description = "The "
            + "active cart's per-seller breakdown as the placement would freeze it: the "
            + "re-priced subtotal, the best single applicable promotion, the voucher's "
            + "discount (validated read-only — the redemption happens at placement), and the "
            + "jurisdiction's tax component. A refused voucher answers 409 BEFORE any money or "
            + "stock moves.")
    public ResponseEntity<java.util.List<OrderResponses.PlacementPreviewResponse>> previewPlacement(
            @org.springframework.lang.Nullable @RequestBody(required = false) PlaceOrderRequest request,
            Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(ordersService.previewPlacement(caller,
                        request == null ? null : request.voucherCode(),
                        request == null || request.fulfillment() == null ? null
                                : request.fulfillment().toSelection()).stream()
                .map(OrderResponses.PlacementPreviewResponse::of).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order", description = "Buyer-scoped: the order's own buyer "
            + "(or ADMIN); anyone else gets an honest 404 — existence itself is private.")
    public ResponseEntity<OrderResponses.OrderResponse> getById(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(OrderResponses.OrderResponse.of(
                ordersService.getOrderDetailForUser(id, authentication)));
    }

    @GetMapping
    @Operation(summary = "List the caller's orders", description = "The caller's own order "
            + "history, newest-first, paginated.")
    public ResponseEntity<PagedResponse<OrderResponses.OrderResponse>> listMine(
            Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(ordersService.listByConsumer(caller, pageable, authentication)
                .map(OrderResponses.OrderResponse::of)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an order", description = "Cancels the caller's order while it "
            + "is still open (PLACED or CONFIRMED); a fulfilled order answers 409 — the machine's "
            + "terminal states do not reopen. The stock reservation is released and the money "
            + "settles through the existing payments engine (cancel the unpaid intent, fully "
            + "refund the collected one).")
    public ResponseEntity<OrderResponses.OrderResponse> cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelOrderRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(OrderResponses.OrderResponse.of(
                ordersService.cancelForUser(id, request.reason(), authentication)));
    }

    /**
     * Stage 6 (ADR-0002): the buyer's payment surface — the order's intent
     * through the EXISTING payments engine (one intent per order, the
     * deterministic idempotency key). The response is the engine's own
     * details carrier — the client confirms the payment against it and the
     * settlement's COMPLETED event auto-confirms the machine.
     */
    @PostMapping("/{id}/payment-intent")
    @Operation(summary = "Create (or return) the order's payment intent", description = "Links "
            + "the order to exactly one payment intent from the existing payments engine "
            + "(idempotent by the order key). Only a PLACED order can open an intent; the "
            + "settlement's COMPLETED event auto-confirms the order.")
    public ResponseEntity<com.marketplace.shared.api.PaymentIntentDetails> requestPaymentIntent(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ordersService.requestPaymentIntent(id, authentication));
    }

    public record CancelOrderRequest(
            @NotBlank @Size(max = 500) String reason) {
    }

    /** The Phase-3 placement command: the optional voucher code. */
    public record PlaceOrderRequest(
            @Size(max = 64) String voucherCode,
            FulfillmentRequest fulfillment) {
    }

    /**
     * The V224 checkout selection — the method and (for a platform
     * delivery) the destination snapshot.
     */
    public record FulfillmentRequest(
            @jakarta.validation.constraints.NotNull FulfillmentMethod method,
            @Size(max = 250) String destinationAddress,
            Double destinationLatitude,
            Double destinationLongitude) {

        OrdersService.FulfillmentSelection toSelection() {
            return new OrdersService.FulfillmentSelection(
                    method, destinationAddress, destinationLatitude, destinationLongitude);
        }
    }
}
