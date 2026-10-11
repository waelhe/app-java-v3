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
     * Cart to order: the placement transaction — no body (the cart IS the
     * order's content; the total is derived, never caller-supplied). Chained
     * through the service's view assembly: the HTTP boundary never declares
     * an entity local (the controllersMustNotDependOnJpaEntities rule).
     */
    @PostMapping
    @Operation(summary = "Place an order from the cart", description = "Freezes the caller's "
            + "active cart lines into an order (PLACED) and tombstones the cart — one atomic "
            + "transaction. The total is derived from the frozen lines; an empty or absent cart "
            + "answers 409.")
    public ResponseEntity<OrderResponses.OrderResponse> place(Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(OrderResponses.OrderResponse.of(ordersService.place(caller)));
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
}
