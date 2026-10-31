package com.marketplace.orders.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * P4 (the canonical Saleor plan §5-P4 — the Saleor {@code order} and
 * {@code orders} read roots, packet campaign-b5-b): the bounded order
 * projection the orders owner exposes at the GraphQL boundary. Only
 * facts the {@code orders} row owns travel here: the native
 * {@code id}, the placement instant {@code placedAt} (the row's own
 * creation — the factory's own fact), and the order's own lifecycle
 * {@code status} (the platform's PLACED/CONFIRMED/FULFILLED/CANCELLED
 * state name — the boundary's mapper renders it through Saleor's own
 * OrderStatus vocabulary). Unowned Saleor fields (the metadata
 * family, number — no human-readable order-number column, user — the
 * identity projection lands with the buyer-surface slice, the
 * channel-scoped money/tax totals — money stays off the wire per the
 * frozen money contract, billing/shipping addresses — no address
 * store (the D-ADDR-001 decision), lines/fulfillments/deliveries —
 * the line and fulfillment projections land with their own slices,
 * trackingClientId, languageCode, externalReference, updatedAt,
 * events, discounts, payments, giftCards, invoices, SEO) stay
 * undeclared at the boundary until an owning capability exists —
 * never faked.
 *
 * @param id       the native {@code orders} primary key
 * @param placedAt the order's placement instant (never null)
 * @param status   the platform lifecycle state name (never null)
 */
public record OrderView(UUID id, Instant placedAt, String status) {
}
