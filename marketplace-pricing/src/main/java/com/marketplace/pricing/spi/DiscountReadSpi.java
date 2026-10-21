package com.marketplace.pricing.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor Discounts-domain
 * {@code promotion}/{@code promotions}/{@code voucher}/{@code vouchers}
 * read roots): the pricing owner's read seam for the platform's discount
 * policy collections. Authorization lives in the owning use case
 * ({@code DiscountReadService} enforces the platform's ADMIN capability —
 * the server-side mapping of Saleor's staff MANAGE_DISCOUNTS-class
 * requirement at the use-case boundary, the RBAC divergence row), never
 * in a UI route guard and never duplicated at the GraphQL boundary.
 */
@NamedInterface("pricing-spi")
public interface DiscountReadSpi {

    /**
     * The promotion projection by the native UUID behind the typed
     * global ID. An unknown but well-formed UUID answers empty (the safe
     * absence the nullable root carries — never an error, never a
     * fabricated policy).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the promotion projection, empty when no row matches
     */
    Optional<PromotionView> findPromotionById(UUID id, Authentication authentication);

    /**
     * One bounded page of the promotion policy collection as a
     * forward-only keyset scroll in the requested sort (the owning field
     * + the {@code id} tiebreaker — the {@code scrollStaffUsers} keyset
     * precedent). The returning page carries the collection total
     * Saleor's {@code totalCount} field expects.
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap;
     *                       an unlimited limit answers the whole bounded
     *                       policy collection)
     * @return the page of promotion projections plus the collection total
     */
    PromotionPage scrollPromotions(Authentication authentication,
                                   PromotionSort sort,
                                   KeysetScrollPosition position,
                                   Limit limit);

    /**
     * The voucher projection by the native UUID behind the typed global
     * ID, including the honest usage count (redemption rows counted —
     * never a stored counter). An unknown but well-formed UUID answers
     * empty (the safe absence the nullable root carries — never an
     * error, never a fabricated policy).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the voucher projection, empty when no row matches
     */
    Optional<VoucherView> findVoucherById(UUID id, Authentication authentication);

    /**
     * One bounded page of the voucher policy collection as a
     * forward-only keyset scroll in the requested sort (the owning field
     * + the {@code id} tiebreaker). The returning page carries the
     * collection total Saleor's {@code totalCount} field expects.
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap;
     *                       an unlimited limit answers the whole bounded
     *                       policy collection)
     * @return the page of voucher projections plus the collection total
     */
    VoucherPage scrollVouchers(Authentication authentication,
                               VoucherSort sort,
                               KeysetScrollPosition position,
                               Limit limit);
}
