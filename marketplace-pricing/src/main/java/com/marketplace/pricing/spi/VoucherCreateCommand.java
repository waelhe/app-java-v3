package com.marketplace.pricing.spi;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucherCreate}
 * mutation root): the create command. The Saleor-owned field set plus
 * the platform-extension discount facts the row's NOT NULL policy
 * requires (the same "platform-only fields as extensions" policy the
 * Category wire type established).
 *
 * <p>The temporal fields are ISO-8601 strings — the GraphQL surface's
 * own documented no-new-scalar decision (no DateTime scalar is declared
 * anywhere on this surface; dates render as ISO-8601 strings on the
 * read side and bind as strings on the write side).</p>
 *
 * @param name                the optional display name
 * @param code                the voucher code (4..64 uppercase
 *                            letters/digits/dashes — the entity's own
 *                            contract)
 * @param discountValueType   PERCENTAGE or FIXED (null is INVALID on
 *                            create — the row's NOT NULL column)
 * @param discountValue       PERCENTAGE: 1..10000 basis points; FIXED:
 *                            positive minor amount (the row's own CHECKs)
 * @param currency            the ISO-4217 denomination (3 uppercase
 *                            letters — the row's NOT NULL column)
 * @param minSpend            the minimum spend in minor units (&gt;= 0;
 *                            null defaults the row to 0)
 * @param startDate           the ISO-8601 start (null opens an
 *                            unbounded window)
 * @param endDate             the ISO-8601 end (null = no end)
 * @param usageLimit          the global redemption limit (null =
 *                            unlimited; &gt; 0 when present)
 * @param applyOncePerCustomer true maps the row's per-user limit to 1
 *                            (the read side's own flattening semantics)
 */
public record VoucherCreateCommand(String name, String code, String discountValueType,
                                   Long discountValue, String currency, Long minSpend,
                                   String startDate, String endDate,
                                   Long usageLimit, Boolean applyOncePerCustomer) {
}
