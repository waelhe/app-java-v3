package com.marketplace.shared.api;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * Seller-side order workflow boundary. The orders module remains authoritative
 * for seller ownership, transition guards, stock commit/release, audit and
 * domain events. Transport adapters receive only frozen projections.
 */
@NamedInterface("seller-order-management")
public interface SellerOrderManagementPort {

    List<OrderCommercePort.OrderView> listSoldOrders(int limit, Authentication authentication);
    OrderCommercePort.OrderView getSoldOrder(UUID orderId, Authentication authentication);
    OrderCommercePort.OrderView confirmSoldOrder(UUID orderId, Authentication authentication);
    OrderCommercePort.OrderView fulfillSoldOrder(UUID orderId, Authentication authentication);
    OrderCommercePort.OrderView cancelSoldOrder(UUID orderId, String reason, Authentication authentication);
}
