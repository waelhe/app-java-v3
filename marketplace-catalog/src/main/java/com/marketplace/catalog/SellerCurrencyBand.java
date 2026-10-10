package com.marketplace.catalog;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): one currency band of
 * the seller summary — a CLOSED projection (projections.html: the
 * getter names bound to the query's select aliases). The bands are
 * grouped per currency because a min/max price RANGE is only honest
 * within one currency — a seller whose products span two currencies
 * gets two bands, never one meaningless cross-currency range (the
 * honest-aggregate discipline: the summary reports what the data
 * actually says, never a convenient merge).
 */
public interface SellerCurrencyBand {

    /** The band's ISO-4217 currency — the group-by key (the {@code currency} alias). */
    String getCurrency();

    /** The band's live product count (the {@code productCount} alias). */
    long getProductCount();

    /** The band's lowest price in minor units (the {@code minPriceMinor} alias). */
    long getMinPriceMinor();

    /** The band's highest price in minor units (the {@code maxPriceMinor} alias). */
    long getMaxPriceMinor();
}
