package com.marketplace.orders;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
     * Adds a line — or raises the quantity when the product is already in
     * the cart (the unique (cart, product) key collapses the duplicate add).
     */
    @PostMapping("/items")
    @Operation(summary = "Add a cart line", description = "Adds the product line to the caller's "
            + "active cart; adding an already-present product raises its quantity instead of "
            + "creating a second line. The amount snapshot fields are the buyer-agreement record "
            + "the placement will freeze; the store's authoritative product pricing arrives with "
            + "the M1 store root.")
    public ResponseEntity<OrderResponses.CartItemResponse> addItem(
            @Valid @RequestBody AddCartItemRequest request, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        OrderResponses.CartItemResponse response = OrderResponses.CartItemResponse.of(
                ordersService.addCartItem(caller, request.productId(), request.quantity(),
                        request.unitAmountMinor(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    public record AddCartItemRequest(
            @NotNull UUID productId,
            @Min(1) @Max(1000) int quantity,
            @Min(0) long unitAmountMinor,
            @NotBlank @Size(min = 3, max = 3) String currency) {
    }
}
