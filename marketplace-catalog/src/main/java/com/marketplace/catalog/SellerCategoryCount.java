package com.marketplace.catalog;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): one category line of
 * the seller summary — a CLOSED projection (projections.html: the
 * getter names bound to the query's select aliases). The lines are the
 * seller's live product count per store-category code — the storefront
 * shelf layout («ما الذي يبيعه هذا البائع»), resolved against the
 * store categories dictionary by the client through the dictionary's
 * own public shape (the code is the stable API-facing key, the V116
 * identity stance).
 */
public interface SellerCategoryCount {

    /** The category's stable code — the group-by key (the {@code categoryCode} alias). */
    String getCategoryCode();

    /** The category's live product count (the {@code productCount} alias). */
    long getProductCount();
}
