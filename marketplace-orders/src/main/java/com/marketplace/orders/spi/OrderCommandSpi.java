package com.marketplace.orders.spi;

import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * The orders owner's command boundary for management operations consumed by
 * transport adapters. Authentication is resolved by Spring Security at the
 * owning use-case boundary; callers receive an owned projection rather than
 * a persistence entity.
 */
@NamedInterface("orders-spi")
public interface OrderCommandSpi {

    /**
     * Cancel an order through the orders owner's guarded state machine.
     * The implementation restricts this management command to ADMIN callers.
     *
     * @param orderId native order ID decoded by the transport boundary
     * @return the cancelled order's bounded read projection
     */
    OrderView cancelAsAdmin(UUID orderId);

    /** Confirm a placed order through the same guarded lifecycle owner. */
    OrderView confirmAsAdmin(UUID orderId);

    /** Bulk-cancel through the same transaction and guarded state machine. */
    OrderBulkCancelResult bulkCancelAsAdmin(java.util.List<UUID> orderIds);
}
