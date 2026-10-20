package com.marketplace.orders.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * Seller-side order workflow boundary. The orders module remains authoritative
 * for seller ownership, transition guards, stock commit/release, audit and
 * domain events. Transport adapters receive only frozen projections.
 *
 * <p><b>Placement (the ArchitectureRulesTest verdict, the ProductAdminSpi
 * pattern):</b> the seam carries Spring Security's {@code Authentication}, so
 * it lives in the OWNING module's {@code spi} package — {@code shared.api} is
 * the framework-neutral zone by the #429 build gate.
 */
@NamedInterface("seller-order-management")
public interface SellerOrderManagementPort {

    List<OrderCommercePort.OrderView> listSoldOrders(int limit, Authentication authentication);
    OrderCommercePort.OrderView getSoldOrder(UUID orderId, Authentication authentication);
    OrderCommercePort.OrderView confirmSoldOrder(UUID orderId, Authentication authentication);
    OrderCommercePort.OrderView fulfillSoldOrder(UUID orderId, Authentication authentication);
    OrderCommercePort.OrderView cancelSoldOrder(UUID orderId, String reason, Authentication authentication);
}
