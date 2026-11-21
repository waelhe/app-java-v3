package com.marketplace.orders.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * The orders owner's checkout boundary over the existing active-cart use
 * cases. The transport adapter resolves typed global IDs and delegates here;
 * ownership and lifecycle gates stay at the owning use-case boundary.
 */
@NamedInterface("orders-spi")
public interface CheckoutSpi {

    /** The active checkout owned by the caller (ADMIN may inspect any). */
    CheckoutView findCheckout(UUID cartId, Authentication authentication);

    /** Add variant lines to the caller's active checkout. */
    CheckoutView addLines(UUID cartId, List<LineAdd> lines, Authentication authentication);

    /** Update variant-line quantities in the caller's active checkout. */
    CheckoutView updateLines(UUID cartId, List<LineUpdate> lines, Authentication authentication);

    /** Remove lines from the caller's active checkout. */
    CheckoutView deleteLines(UUID cartId, List<UUID> lineIds, Authentication authentication);

    /** Delete any checkout as the management capability. */
    void deleteAsAdmin(UUID cartId);

    /** A variant line to add, in the caller's requested order. */
    record LineAdd(UUID variantId, int quantity) {
    }

    /** A line update by line ID and/or deprecated variant ID. */
    record LineUpdate(UUID lineId, UUID variantId, Integer quantity) {
    }
}
