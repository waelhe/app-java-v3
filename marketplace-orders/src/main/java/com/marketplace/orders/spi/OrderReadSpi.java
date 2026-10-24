package com.marketplace.orders.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * P4 (the canonical Saleor plan §5-P4 — the Saleor {@code order} and
 * {@code orders} read roots, packet campaign-b5-b): the orders owner's
 * read seam for the store's order population (the SAME authoritative
 * {@code orders} rows the placement machine writes — never a duplicate
 * store). Authorization lives in the owning use case
 * ({@code OrderReadService}): these are Saleor MANAGEMENT roots —
 * "Requires one of the following permissions: MANAGE_ORDERS" — mapped
 * through the platform's ADMIN capability (the SaleorPermissionCatalog
 * contract, enforced at the owning use-case boundary by the same
 * method-security mechanism the identity module's staff-user reads
 * established). There is no public population on this root: a
 * non-ADMIN caller is denied (the authorization failure), and the
 * buyer's own-order surface is the User.orders field's own later
 * slice, never this root.
 */
@NamedInterface("orders-spi")
public interface OrderReadSpi {

    /**
     * The order lookup by the native UUID behind the typed global ID —
     * ADMIN-enforced at this owning use-case boundary (Saleor requires
     * MANAGE_ORDERS on the order surface). An unknown but well-formed
     * UUID answers empty (the safe absence the nullable root carries).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller (the method-security enforcement's
     *                       own context)
     * @return the order projection, empty when no row matches
     */
    Optional<OrderView> findOrderById(UUID id, Authentication authentication);

    /**
     * One bounded page of the order population as a REAL SQL
     * forward-only keyset scroll in the requested sort (the owning
     * field + the {@code id} tiebreaker — the
     * {@code scrollStaffUsers} repository-keyset precedent; the orders
     * table is populated commerce data, never bounded reference data).
     * The population is the whole live order set (the ADMIN-only
     * management surface — no public slice exists on this root), and
     * the returning page's total is the SAME population's count.
     *
     * @param authentication the caller (the method-security enforcement's
     *                       own context)
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap)
     * @return the page of order projections plus the population total
     */
    OrderPage scrollOrders(Authentication authentication,
                           OrderSort sort,
                           KeysetScrollPosition position,
                           Limit limit);
}
