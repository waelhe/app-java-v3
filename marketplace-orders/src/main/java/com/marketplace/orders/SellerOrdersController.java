package com.marketplace.orders;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The merchant's order surface (the Saleor study's Phase-1 slice, PR #602)
 * — the HTTP arrival the buyer surface promised on day one: «the
 * merchant-side transitions (confirm, fulfill) are the machine's
 * service-level machinery — their HTTP surface arrives with the store's
 * merchant console (compliance plan C.7/M1, unit A-17), guarded and
 * event-publishing from day one, exercised by the journey gate».
 *
 * <p><b>Scope:</b> the order's own seller reads the frozen snapshot,
 * lists their sold orders, and drives the machine's merchant edges —
 * confirm, fulfill, and the administrative cancel — each answering the
 * machine's new state as the post-command read-back. ADMIN shares the
 * reads and the commands (the operational authority); anyone else reads
 * the module's honest 404 — existence itself is private (the A-03
 * precedent, the buyer's twin). The buyer's own surface
 * ({@code OrdersController}) is untouched: the payment-intent link stays
 * buyer-only, and the buyer's cancel keeps its own route.
 *
 * <p><b>Ownership (the A1 convention):</b> {@code orders.seller_id}
 * physically carries the provider's {@code users.id}, so the service's
 * gate reads the ruling from the row itself and compares it directly
 * against the caller's user id — the {@code verifyProviderOwnership}
 * pattern (no profile resolution, no client-supplied provider id).
 *
 * <p><b>One machine, no second engine:</b> every command delegates to the
 * {@code OrdersService} machine edge the settlement listener and the
 * buyer's surface already ride — the guards, the stock commit/release
 * edges (ADR-0002), the events, and the Envers audit are the SAME
 * load-bearing machinery; this surface only decides WHO may pull the
 * lever. No new events, no schema change, no cross-module access.
 */
@RestController
@RequestMapping(value = ApiConstants.ORDERS + "/seller", version = "1.0")
public class SellerOrdersController {

    private final OrdersService ordersService;

    public SellerOrdersController(OrdersService ordersService) {
        this.ordersService = ordersService;
    }

    @GetMapping("/me")
    @Operation(summary = "List the orders I sold", description = "The calling seller's own "
            + "sold-order history, newest-first, paginated (ADR-0010: one order per seller — "
            + "each split order is its own row here). A caller with no sales reads an honest "
            + "empty page.")
    public ResponseEntity<PagedResponse<OrderResponses.OrderResponse>> listMine(
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(ordersService.listSoldByCaller(authentication, pageable)
                .map(OrderResponses.OrderResponse::of)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one sold order", description = "Seller-scoped: the order's own "
            + "seller (or ADMIN) reads the frozen snapshot — lines, derived total, and the "
            + "machine's state. Anyone else gets an honest 404 — existence itself is private.")
    public ResponseEntity<OrderResponses.OrderResponse> getById(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(OrderResponses.OrderResponse.of(
                ordersService.getOrderDetailForSeller(id, authentication)));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a placed order (merchant)", description = "The merchant's "
            + "acceptance: PLACED → CONFIRMED. Only the order's own seller (or ADMIN); the "
            + "answer is the machine's new state — the post-command read-back. An illegal "
            + "edge answers the house 409.")
    public ResponseEntity<OrderResponses.OrderResponse> confirm(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(OrderResponses.OrderResponse.of(
                ordersService.confirmForSeller(id, authentication)));
    }

    @PostMapping("/{id}/fulfill")
    @Operation(summary = "Fulfill a confirmed order (merchant)", description = "The merchant's "
            + "completion: CONFIRMED → FULFILLED (terminal) — the stock reservation commits "
            + "into a real deduction (ADR-0002). Only the order's own seller (or ADMIN); the "
            + "answer is the machine's new state. A fulfilled order does not reopen.")
    public ResponseEntity<OrderResponses.OrderResponse> fulfill(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(OrderResponses.OrderResponse.of(
                ordersService.fulfillForSeller(id, authentication)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an open order (merchant)", description = "The merchant's "
            + "administrative cancel: PLACED/CONFIRMED → CANCELLED — the stock reservation "
            + "releases exactly once and the money settles through the existing payments "
            + "engine (cancel the unpaid intent, fully refund the collected one). Only the "
            + "order's own seller (or ADMIN); the answer is the machine's new state.")
    public ResponseEntity<OrderResponses.OrderResponse> cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelOrderRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(OrderResponses.OrderResponse.of(
                ordersService.cancelForSeller(id, request.reason(), authentication)));
    }

    public record CancelOrderRequest(
            @NotBlank @Size(max = 500) String reason) {
    }
}
