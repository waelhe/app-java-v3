package com.marketplace.orders.spi;

import java.util.List;
import java.util.UUID;

/**
 * The bounded checkout projection the orders owner exposes at the GraphQL
 * boundary: the active cart's native ID, its variant lines in their stored
 * order, and the total quantity. Money, addresses, channel, and delivery
 * facts stay off this projection until their owning capabilities exist.
 *
 * @param id       the native {@code carts} primary key
 * @param lines    the checkout's variant lines (never null)
 * @param quantity the sum of the lines' quantities
 */
public record CheckoutView(UUID id, List<Line> lines, int quantity) {

    public CheckoutView {
        lines = List.copyOf(lines);
    }

    /**
     * @param id        the native {@code cart_items} primary key
     * @param variantId the referenced product variant (never null)
     * @param quantity  the line quantity (at least one)
     */
    public record Line(UUID id, UUID variantId, int quantity) {
    }
}
