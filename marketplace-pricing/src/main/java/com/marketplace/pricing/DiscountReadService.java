package com.marketplace.pricing;

import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.pricing.spi.PromotionPage;
import com.marketplace.pricing.spi.PromotionSort;
import com.marketplace.pricing.spi.PromotionView;
import com.marketplace.pricing.spi.VoucherPage;
import com.marketplace.pricing.spi.VoucherSort;
import com.marketplace.pricing.spi.VoucherView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor Discounts-domain
 * {@code promotion}/{@code promotions}/{@code voucher}/{@code vouchers}
 * read roots): the owning use case for the platform's discount policy
 * collections. All four reads are ADMIN-enforced at this boundary (the
 * platform's server-side mapping of Saleor's staff
 * MANAGE_DISCOUNTS-class requirement — the RBAC divergence row),
 * read-only, and own every business decision the boundary needs.
 *
 * <p><b>The scrolls execute in memory over the bounded policy
 * collections</b> — the honest scope decision the
 * {@code ProductTypeReadService} precedent established for reference
 * data: a shop's promotion and voucher policy rows are authored
 * reference collections, and the scroll keeps the SAME semantics the
 * roster's SQL keyset executes — the cursor identifies one row's own
 * sort key {@code (property, id)}, the page follows it strictly in the
 * requested direction, and {@code hasNext} states whether elements
 * remain — never an absolute offset. Soft-deleted rows are absent by
 * the entity's own {@code @SoftDelete} filter (the same visibility
 * every pricing read applies). If a collection ever grows beyond
 * policy-reference scale, the SQL keyset methods (the
 * {@code UserRepository} precedent) replace the in-memory window
 * without changing the seam.</p>
 *
 * <p><b>The executed sort vocabulary is the non-null owning columns</b>
 * ({@code PromotionSort}/{@code VoucherSort}): a NULL property value
 * cannot travel in the framework's map-based keyset cursor, so the
 * optional window/name/limit columns never execute as keyset sorts on
 * this contract — the boundary answers the explicit classified error
 * for them instead.</p>
 */
@Service
public class DiscountReadService implements DiscountReadSpi {

    private final PromotionRepository promotionRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository voucherRedemptionRepository;

    public DiscountReadService(PromotionRepository promotionRepository,
                               VoucherRepository voucherRepository,
                               VoucherRedemptionRepository voucherRedemptionRepository) {
        this.promotionRepository = promotionRepository;
        this.voucherRepository = voucherRepository;
        this.voucherRedemptionRepository = voucherRedemptionRepository;
    }

    /**
     * The promotion lookup by the native UUID behind the typed global ID
     * — ADMIN-enforced. An unknown but well-formed UUID answers empty:
     * the nullable root's own honest absence, never an error and never a
     * fabricated policy.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<PromotionView> findPromotionById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return promotionRepository.findById(id).map(DiscountReadService::toPromotionView);
    }

    /**
     * The promotion policy collection's forward-only keyset scroll —
     * ADMIN-enforced, the owning sort field (NAME or CREATED_AT — the
     * non-null columns) with the {@code id} tiebreaker, plus the
     * collection total Saleor's {@code totalCount} field carries.
     *
     * <p>The boundary's {@code KeysetCursorGate} has already validated
     * the cursor's {@code (property, id)} key set against this scroll's
     * own vocabulary; a position that is not the initial one must still
     * carry those keys here, or the honest
     * {@link IllegalArgumentException} answers — never a silent
     * no-op.</p>
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PromotionPage scrollPromotions(Authentication authentication,
                                          PromotionSort sort,
                                          KeysetScrollPosition position,
                                          Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A promotion sort is required");
        }
        List<PromotionView> promotions = new ArrayList<>();
        for (Promotion promotion : promotionRepository.findAll()) {
            promotions.add(toPromotionView(promotion));
        }

        // The deterministic ordering — the owning sort field with the id
        // tiebreaker, both in the requested direction (the roster's SQL
        // keyset methods' own shape: ...AscIdAsc / ...DescIdDesc). Both
        // executed fields are non-null by the entity's own contract, so
        // the comparator never sees a null key.
        Comparator<String> nameOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<Instant> createdAtOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UUID> idOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<PromotionView> fieldOrder = sort.field() == PromotionSort.Field.NAME
                ? Comparator.comparing(PromotionView::name, nameOrder)
                : Comparator.comparing(PromotionView::createdAt, createdAtOrder);
        promotions.sort(fieldOrder.thenComparing(PromotionView::id, idOrder));

        // The keyset predicate: elements strictly AFTER the cursor's own
        // (property, id) sort key in the requested direction — the same
        // "greater than the sort key" the framework's keyset predicate
        // builds for the SQL scrolls.
        List<PromotionView> scrollable = promotions;
        if (position != null && !position.isInitial()) {
            if (!(position instanceof KeysetScrollPosition keyset)
                    || !keyset.scrollsForward()
                    || keyset.getKeys().get("id") == null) {
                throw new IllegalArgumentException(
                        "The promotion scroll position must be a forward keyset over the sort's own (property, id)");
            }
            UUID cursorId = keyset.getKeys().get("id") instanceof UUID uuid
                    ? uuid
                    : UUID.fromString(String.valueOf(keyset.getKeys().get("id")));
            if (sort.field() == PromotionSort.Field.NAME) {
                if (keyset.getKeys().get("name") == null) {
                    throw new IllegalArgumentException(
                            "The promotion scroll position must be a forward keyset over (name, id)");
                }
                String cursorName = String.valueOf(keyset.getKeys().get("name"));
                scrollable = promotions.stream()
                        .filter(promotion -> {
                            int byName = nameOrder.compare(promotion.name(), cursorName);
                            return byName != 0 ? byName > 0
                                    : idOrder.compare(promotion.id(), cursorId) > 0;
                        })
                        .toList();
            } else {
                if (keyset.getKeys().get("createdAt") == null) {
                    throw new IllegalArgumentException(
                            "The promotion scroll position must be a forward keyset over (createdAt, id)");
                }
                Instant cursorCreatedAt = keyset.getKeys().get("createdAt") instanceof Instant instant
                        ? instant
                        : Instant.parse(String.valueOf(keyset.getKeys().get("createdAt")));
                scrollable = promotions.stream()
                        .filter(promotion -> {
                            int byCreatedAt = createdAtOrder.compare(promotion.createdAt(), cursorCreatedAt);
                            return byCreatedAt != 0 ? byCreatedAt > 0
                                    : idOrder.compare(promotion.id(), cursorId) > 0;
                        })
                        .toList();
            }
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<PromotionView> content = scrollable.subList(0, pageSize);
        Window<PromotionView> window = Window.from(content, index ->
                ScrollPosition.forward(Map.of(
                        sortProperty(sort), sortValueAt(sort, content.get(index)),
                        "id", content.get(index).id())),
                hasNext);
        return new PromotionPage(window, promotions.size());
    }

    /**
     * The voucher lookup by the native UUID behind the typed global ID —
     * ADMIN-enforced, including the honest usage count (redemption rows
     * counted through the owner's own accounting seam — never a stored
     * counter). An unknown but well-formed UUID answers empty: the
     * nullable root's own honest absence, never an error and never a
     * fabricated policy.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<VoucherView> findVoucherById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return voucherRepository.findById(id)
                .map(voucher -> toVoucherView(voucher, voucherRedemptionRepository.countByVoucherId(id)));
    }

    /**
     * The voucher policy collection's forward-only keyset scroll —
     * ADMIN-enforced, the owning CODE sort with the {@code id}
     * tiebreaker, plus the collection total Saleor's {@code totalCount}
     * field carries.
     *
     * <p>The sort and the keyset predicate execute over the ENTITY rows
     * (both executed fields live on the row), and the page slice alone
     * maps to projections — the per-row usage counts run once per PAGE
     * row through the redemption accounting seam, never once per
     * collection row. The boundary's {@code KeysetCursorGate} has
     * already validated the cursor's {@code (code, id)} key set; a
     * position that is not the initial one must still carry those keys
     * here, or the honest {@link IllegalArgumentException} answers —
     * never a silent no-op.</p>
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public VoucherPage scrollVouchers(Authentication authentication,
                                      VoucherSort sort,
                                      KeysetScrollPosition position,
                                      Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A voucher sort is required");
        }
        List<Voucher> vouchers = new ArrayList<>(voucherRepository.findAll());

        // The deterministic ordering — the owning CODE field with the id
        // tiebreaker, both in the requested direction (the roster's SQL
        // keyset methods' own shape). CODE is non-null by the entity's
        // own contract, so the comparator never sees a null key.
        Comparator<String> codeOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UUID> idOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        vouchers.sort(Comparator
                .comparing(Voucher::getCode, codeOrder)
                .thenComparing(Voucher::getId, idOrder));

        // The keyset predicate: elements strictly AFTER the cursor's own
        // (code, id) sort key in the requested direction.
        List<Voucher> scrollable = vouchers;
        if (position != null && !position.isInitial()) {
            if (!(position instanceof KeysetScrollPosition keyset)
                    || !keyset.scrollsForward()
                    || keyset.getKeys().get("code") == null
                    || keyset.getKeys().get("id") == null) {
                throw new IllegalArgumentException(
                        "The voucher scroll position must be a forward keyset over (code, id)");
            }
            String cursorCode = String.valueOf(keyset.getKeys().get("code"));
            UUID cursorId = keyset.getKeys().get("id") instanceof UUID uuid
                    ? uuid
                    : UUID.fromString(String.valueOf(keyset.getKeys().get("id")));
            scrollable = vouchers.stream()
                    .filter(voucher -> {
                        int byCode = codeOrder.compare(voucher.getCode(), cursorCode);
                        return byCode != 0 ? byCode > 0
                                : idOrder.compare(voucher.getId(), cursorId) > 0;
                    })
                    .toList();
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<Voucher> content = scrollable.subList(0, pageSize);
        List<VoucherView> contentViews = new ArrayList<>(content.size());
        for (Voucher voucher : content) {
            contentViews.add(toVoucherView(voucher,
                    voucherRedemptionRepository.countByVoucherId(voucher.getId())));
        }
        Window<VoucherView> window = Window.from(contentViews, index ->
                ScrollPosition.forward(Map.of(
                        "code", contentViews.get(index).code(),
                        "id", contentViews.get(index).id())),
                hasNext);
        return new VoucherPage(window, vouchers.size());
    }

    /**
     * The bounded promotion projection — only facts the {@code promotions}
     * row owns. {@code orderLevel} is the entity's own application-level
     * contract (an order-level policy by construction — never a
     * catalogue predicate); the value facts stay with the owner's admin
     * surface per the money contract (see {@link PromotionView}).
     */
    private static PromotionView toPromotionView(Promotion promotion) {
        return new PromotionView(
                promotion.getId(),
                promotion.getName(),
                true,
                promotion.getStartsAt(),
                promotion.getEndsAt(),
                promotion.getCreatedAt(),
                promotion.getUpdatedAt());
    }

    /**
     * The bounded voucher projection — only facts the {@code vouchers}
     * row and its redemption accounting own. {@code applyOncePerCustomer}
     * is the truthful flattening of the per-user limit (true exactly when
     * the limit is 1); the value facts stay with the owner's admin
     * surface per the money contract (see {@link VoucherView}).
     */
    private static VoucherView toVoucherView(Voucher voucher, long timesUsed) {
        return new VoucherView(
                voucher.getId(),
                voucher.getCode(),
                voucher.getName(),
                voucher.getCurrency(),
                voucher.getDiscountType() == Voucher.DiscountType.PERCENTAGE
                        ? VoucherView.DiscountType.PERCENTAGE
                        : VoucherView.DiscountType.FIXED,
                voucher.getMaxRedemptions(),
                timesUsed,
                voucher.getMaxRedemptionsPerUser() != null
                        && voucher.getMaxRedemptionsPerUser() == 1L,
                voucher.getStartsAt(),
                voucher.getEndsAt());
    }

    /** The keyset position's sort-property name for the executed sort. */
    private static String sortProperty(PromotionSort sort) {
        return sort.field() == PromotionSort.Field.NAME ? "name" : "createdAt";
    }

    /** The keyset position's sort-property value for one page element. */
    private static Object sortValueAt(PromotionSort sort, PromotionView view) {
        return sort.field() == PromotionSort.Field.NAME ? view.name() : view.createdAt();
    }
}
