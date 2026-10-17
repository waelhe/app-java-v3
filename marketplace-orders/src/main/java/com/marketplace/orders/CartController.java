package com.marketplace.orders;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — the buyer's cart surface: a /me
 * family member (identity from the authentication itself — the same family
 * shape as favorites and saved-searches; no id travels the URL).
 */
@RestController
@RequestMapping(value = ApiConstants.CART, version = "1.0")
public class CartController {

    private final OrdersService ordersService;
    private final CurrentUserProvider currentUserProvider;

    public CartController(OrdersService ordersService, CurrentUserProvider currentUserProvider) {
        this.ordersService = ordersService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The buyer's live draft — an implicit create-on-read: an absent cart is
     * not an error state to model over the wire, it is an empty cart.
     */
    @GetMapping
    @Operation(summary = "Get the buyer's cart", description = "The caller's single active cart "
            + "with its lines; an absent cart answers an empty line list (create-on-read).")
    public ResponseEntity<List<OrderResponses.CartItemResponse>> getCart(Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(ordersService.getActiveCartItems(caller).stream()
                .map(OrderResponses.CartItemResponse::of)
                .toList());
    }

    /**
     * Adds a line — or raises the quantity when the SAME purchasable unit
     * is already in the cart (the union key collapses the duplicate add:
     * the product-level line for a product, the variant's own line for a
     * variant — ADR-0012's widened shape).
     *
     * <p>Stage 6 (ADR-0002): the request's amount fields are LEGACY-ONLY —
     * the service never reads them (the line's amount is the store's
     * authoritative pricing resolved inside its transaction — the
     * product's own for a product-level line, the VARIANT's own for a
     * variant line); they stay in the schema as optional-and-deprecated
     * because the OpenAPI compatibility gate's own design is fail-closed
     * on request-body breaks (the removal would be an unrepresentable
     * break). Old clients keep sending them harmlessly; new clients omit
     * them.
     *
     * <p>ADR-0012 (the cart-integration slice): {@code variantId} is
     * OPTIONAL and ADDITIVE — absent keeps the zero-variant rule (the
     * line buys the product-level record exactly as today); present buys
     * the VARIANT under the product, at the variant's own authoritative
     * price and shelf. The service validates that the variant belongs to
     * the request's product (a mismatch is a client inconsistency and
     * answers 400 — never a silent re-point).
     */
    @PostMapping("/items")
    @Operation(summary = "Add a cart line", description = "Adds the product (or variant) line "
            + "to the caller's active cart at the store's authoritative price (the amount is "
            + "the store's own record, never caller-supplied); adding an already-present "
            + "purchasable unit raises its quantity instead of creating a second line. "
            + "A non-ACTIVE product or variant answers 409; a variantId that does not "
            + "belong to the productId answers 400.")
    public ResponseEntity<OrderResponses.CartItemResponse> addItem(
            @Valid @RequestBody AddCartItemRequest request, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        OrderResponses.CartItemResponse response = OrderResponses.CartItemResponse.of(
                ordersService.addCartItem(caller, request.productId(), request.variantId(),
                        request.quantity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    public record AddCartItemRequest(
            @NotNull UUID productId,
            @Schema(description = "ADR-0012: the purchasable variant under the product — "
                    + "optional (absent keeps the product-level line); when present, the "
                    + "line prices and reserves against the VARIANT's own record and must "
                    + "belong to productId (a mismatch answers 400)")
            UUID variantId,
            @Min(1) @Max(1000) int quantity,
            @Schema(deprecated = true, description = "Ignored — the store's authoritative "
                    + "pricing is the only amount source (ADR-0002)")
            Long unitAmountMinor,
            @Schema(deprecated = true, description = "Ignored — the store's authoritative "
                    + "pricing is the only amount source (ADR-0002)")
            String currency) {
    }
}
