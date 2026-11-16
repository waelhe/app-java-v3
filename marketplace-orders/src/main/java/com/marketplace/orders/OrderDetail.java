package com.marketplace.orders;

import java.util.List;

/**
 * A-11 — the service layer's view assembly: the order together with its
 * frozen lines, the pair every read surface answers with. Exists so the
 * HTTP boundary never declares an entity-typed local (the
 * controllersMustNotDependOnJpaEntities architecture rule — controllers
 * speak DTO records only); the DTO factory
 * {@link OrderResponses.OrderResponse#of(OrderDetail)} consumes this
 * record, and the controllers chain it inline.
 */
public record OrderDetail(Order order, List<OrderItem> items) {
}
