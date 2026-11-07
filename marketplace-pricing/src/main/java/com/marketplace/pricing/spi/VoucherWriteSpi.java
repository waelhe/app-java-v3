package com.marketplace.pricing.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucherCreate},
 * {@code voucherUpdate}, {@code voucherDelete} and
 * {@code voucherBulkDelete} mutation roots): the pricing owner's write
 * seam for the voucher policy collection. These are Saleor MANAGEMENT
 * roots ("Requires one of the following permissions: MANAGE_DISCOUNTS"),
 * mapped through the platform's ADMIN capability and enforced HERE at
 * the owning use-case boundary by Spring Security's method security —
 * the same {@code @PreAuthorize} mechanism the module's own discount
 * reads established.
 *
 * <p><b>The result contract is the Saleor payload-error shape's own
 * domain mapping</b> (the registered "Mutation payload errors" ADAPT
 * divergence): domain-level validation outcomes answer as
 * {@link VoucherMutationError} entries on the
 * {@link VoucherMutationResult} — never as thrown exceptions. An empty
 * error list IS the success verdict.</p>
 *
 * <p><b>The input closure is the bounded owned field set</b> of the
 * Saleor {@code VoucherInput} (name, code, startDate, endDate,
 * discountValueType, usageLimit, applyOncePerCustomer) plus the
 * platform-extension discount facts the row's own NOT NULL policy
 * requires and the Saleor closure cannot declare on this no-new-scalar
 * surface (discountValue, currency, minSpend — the "platform-only
 * fields as extensions" policy the Category wire type's own
 * {@code nameAr}/{@code position} extension fields established). The
 * unowned Saleor members (the catalogue scoping family, the codes
 * feature-preview model, countries, onlyForStaff, singleUse) stay
 * undeclared — the recorded divergences.</p>
 */
@NamedInterface("pricing-spi")
public interface VoucherWriteSpi {

    /**
     * Creates one voucher policy row — the Saleor {@code voucherCreate}
     * root's owning use case.
     *
     * @param command the create command (validated again by the owner)
     * @return the result with the created row's projection, or the
     *         domain validation errors
     */
    VoucherMutationResult createVoucher(VoucherCreateCommand command);

    /**
     * Amends one voucher policy row — the Saleor {@code voucherUpdate}
     * root's owning use case. {@code null} command fields preserve the
     * column (the Saleor input's omit-to-preserve semantics).
     *
     * @param id      the row's native UUID (the typed global ID is
     *                decoded at the transport boundary)
     * @param command the amendment command
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID, ALREADY_EXISTS)
     */
    VoucherMutationResult updateVoucher(UUID id, VoucherUpdateCommand command);

    /**
     * Soft-deletes one voucher policy row — the Saleor
     * {@code voucherDelete} root's owning use case.
     *
     * @param id the row's native UUID
     * @return the result with the deleted row's last-known projection,
     *         or the NOT_FOUND domain error
     */
    VoucherMutationResult deleteVoucher(UUID id);

    /**
     * Soft-deletes the given voucher rows — the Saleor
     * {@code voucherBulkDelete} root's owning use case.
     *
     * @param ids the rows' native UUIDs (unknown ids are skipped — the
     *            honest affected count)
     * @return the count of rows actually soft-deleted
     */
    int deleteVouchers(List<UUID> ids);
}
