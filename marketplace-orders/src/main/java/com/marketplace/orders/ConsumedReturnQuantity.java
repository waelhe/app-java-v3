package com.marketplace.orders;

import java.util.UUID;

/**
 * row 6 (agent findings 2026-10-25, CWE-400): one CLOSED projection row of
 * the return-overlap guard's aggregate — the quantity units a frozen order
 * item has already ceded to the counted returns (REQUESTED through
 * COMPLETED; only REJECTED/CANCELLED give the units back). A projection
 * bound to the query's select aliases (projections.html — the house
 * {@code SellerCategoryCount} pattern), produced by ONE fixed aggregate
 * query instead of the per-return line walk the finding measured: a buyer
 * preparing many small returns could replay that walk — one query per
 * historical return, inside the locked transaction — with every rejected
 * request.
 */
public interface ConsumedReturnQuantity {

    /** The frozen order line the units were ceded against (the {@code orderItemId} alias). */
    UUID getOrderItemId();

    /** The ceded units (the {@code consumedQuantity} alias — sum of the counted lines). */
    long getConsumedQuantity();
}
