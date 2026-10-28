package com.marketplace.orders;

import com.marketplace.orders.spi.CheckoutSpi;
import com.marketplace.orders.spi.CheckoutView;
import com.marketplace.orders.spi.OrderCommandSpi;
import com.marketplace.orders.spi.OrderView;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CommercialPricingPort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import com.marketplace.shared.api.OrderPaymentPort;
import com.marketplace.shared.api.OrderDeliveryPort;
import com.marketplace.shared.api.OrderDeliveryDetails;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — the order machine: cart to order to
 * fulfillment, an event-driven state machine per the Modulith events
 * contract ("the publisher publishes the event in its own transaction; the
 * {@code @ApplicationModuleListener} consumer runs AFTER_COMMIT in an
 * independent REQUIRES_NEW unit" — reference/events.html): every
 * transition flips the state, stamps its clock, and publishes its
 * cross-boundary event in the ONE transaction, so the row and the registry
 * entry commit atomically and a consumer failure never rolls the business
 * state back.
 *
 * <p><b>Transition guards:</b> an illegal transition answers
 * {@code ConflictException} (the house 409 contract) naming the machine's
 * edge — the invariant lives in the service (the single writer), is pinned
 * by the DB status CHECK (V113 — membership set), and audited by Envers
 * (one revision per transition).
 *
 * <p><b>The PLACED-no-event discipline (the A-03 measured lesson
 * institutionalized):</b> placement publishes nothing — it is the buyer's
 * own intra-module write, and "an event without a listener is a measured
 * defect". CONFIRMED, FULFILLED and CANCELLED publish their events (the
 * notifications late-lander listeners recorded in the parallel contracts
 * ledger).
 *
 * <p><b>Privacy contract (the A-03 404/403 precedent for private
 * artifacts):</b> reads answer an honest 404 to anyone but the buyer or
 * ADMIN — existence itself is not public information.
 *
 * <p>Stage 6 (ADR-0002 — the commerce fit-gap closure): the cart's amount
 * SOURCE is the store's authoritative pricing ({@link ProductPricingPort}
 * — no caller-supplied amount exists anywhere on the write path, the
 * boundary this unit itself documented); placement reserves the lines'
 * stock through {@link ProductStockPort} (the whole-map atomic write — a
 * failed reservation rolls the placement back) and freezes the CURRENT
 * authoritative price as the buyer-agreement record; cancellation releases
 * the reservation (and the payment engine settles the money through
 * {@code OrderCancelledEvent}); fulfillment commits the reservation into
 * real deductions.
 *
 * <p>Stage ADR-0010 (the ADR-0002 deferral opened): the placement SPLITS a
 * mixed-seller cart into one order per seller — the {@code orders.seller_id}
 * single-value invariant and the ledger's per-seller settlement stand
 * UNCHANGED (one order carries one seller); the split is a cart-level
 * fact. The whole-cart reservation stays all-or-nothing across ALL groups,
 * the cart tombstones once, and PLACED still publishes nothing.
 */
@Service
public class OrdersService implements OrderDeliveryPort, OrderCommandSpi, CheckoutSpi {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrdersService.class);

    private static final int MAX_LINE_QUANTITY = 1000;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductPricingPort productPricingPort;
    private final ProductStockPort productStockPort;
    private final OrderPaymentPort orderPaymentPort;
    private final CommercialPricingPort commercialPricingPort;
    private final OrderPriceComponentRepository orderPriceComponentRepository;
    private final com.marketplace.shared.api.CheckoutDeliveryPort checkoutDeliveryPort;

    public OrdersService(CartRepository cartRepository,
                         CartItemRepository cartItemRepository,
                         OrderRepository orderRepository,
                         OrderItemRepository orderItemRepository,
                         CurrentUserProvider currentUserProvider,
                         ApplicationEventPublisher eventPublisher,
                         ProductPricingPort productPricingPort,
                         ProductStockPort productStockPort,
                         OrderPaymentPort orderPaymentPort,
                         CommercialPricingPort commercialPricingPort,
                         OrderPriceComponentRepository orderPriceComponentRepository,
                         com.marketplace.shared.api.CheckoutDeliveryPort checkoutDeliveryPort) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.productPricingPort = productPricingPort;
        this.productStockPort = productStockPort;
        this.orderPaymentPort = orderPaymentPort;
        this.commercialPricingPort = commercialPricingPort;
        this.orderPriceComponentRepository = orderPriceComponentRepository;
        this.checkoutDeliveryPort = checkoutDeliveryPort;
    }

    // ------------------------------------------------------------------
    // The cart (the buyer's single live draft)
    // ------------------------------------------------------------------

    /**
     * Idempotent by construction: V113's partial-unique index guarantees at
     * most one ACTIVE cart per consumer, so the absent case creates the
     * one row the index will ever admit for this buyer.
     */
    @Transactional
    public Cart getOrCreateActiveCart(UUID consumerId) {
        return cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .orElseGet(() -> cartRepository.save(Cart.activeFor(consumerId)));
    }

    /**
     * Adds a line (or raises the quantity when the SAME purchasable unit
     * is already in the cart — the union key collapses the duplicate add
     * into the quantity bump: the product-level line for a product, the
     * variant's own line for a variant).
     *
     * <p><b>The amount-source boundary (ADR-0002 — the closure of the
     * documented TODO):</b> the line's amount is the store's authoritative
     * pricing, resolved through {@link ProductPricingPort} INSIDE this
     * transaction — no caller-supplied amount exists on the write path.
     * A non-ACTIVE product (suspended/archived/deleted) answers the house
     * 409: the storefront read already hides it, and the write path
     * re-checks the state at its own boundary.
     *
     * <p><b>ADR-0012 (the cart-integration slice): the variant line.</b>
     * A non-null {@code variantId} buys the VARIANT under the product:
     * the amount is the VARIANT's own authoritative price, and the
     * purchasability is BOTH shelf states (the parent's and the variant's
     * — Saleor's publication rule: a suspended product hides its
     * variants). The service resolves the variant's OWN parent and
     * validates it IS the request's product — a mismatch is a client
     * inconsistency that answers 400, never a silent re-point (the same
     * never-trust-caller-input boundary the amount fields obey). A null
     * {@code variantId} keeps the zero-variant rule untouched: the
     * product-level price and shelf stay the authoritative record.
     */
    @Transactional
    public CartItem addCartItem(UUID consumerId, UUID productId, UUID variantId, int quantity) {
        final long unitAmountMinor;
        final String currency;
        if (variantId != null) {
            ProductPricingPort.VariantPrice variant = productPricingPort.priceOfVariant(variantId);
            if (!variant.productId().equals(productId)) {
                throw new BadRequestException("Variant " + variantId + " belongs to product "
                        + variant.productId() + ", not " + productId
                        + " — the line's product must be the variant's own parent");
            }
            if (!variant.purchasable()) {
                throw new ConflictException("Variant " + variantId + " is not purchasable — product "
                        + productId + " is " + variant.productStorefront()
                        + ", variant is " + variant.variantStorefront()
                        + " (both must be ACTIVE)");
            }
            unitAmountMinor = variant.priceMinor();
            currency = variant.currency();
        } else {
            ProductPricingPort.ProductPrice price = productPricingPort.priceOf(productId);
            if (price.storefront() != ProductPricingPort.StorefrontState.ACTIVE) {
                throw new ConflictException("Product " + productId + " is " + price.storefront()
                        + " — only ACTIVE products are purchasable");
            }
            unitAmountMinor = price.priceMinor();
            currency = price.currency();
        }
        Cart cart = getOrCreateActiveCart(consumerId);
        java.util.Optional<CartItem> existing = variantId != null
                ? cartItemRepository.findByCartIdAndProductIdAndVariantId(cart.getId(), productId, variantId)
                : cartItemRepository.findByCartIdAndProductIdAndVariantIdIsNull(cart.getId(), productId);
        return existing
                .map(line -> {
                    line.updateQuantity(line.getQuantity() + quantity);
                    return line;
                })
                .orElseGet(() -> cartItemRepository.save(
                        CartItem.of(cart.getId(), productId, variantId, quantity,
                                unitAmountMinor, currency)));
    }

    @Transactional
    public void removeCartItem(UUID consumerId, UUID itemId) {
        Cart cart = cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Cart"));
        CartItem item = cartItemRepository.findById(itemId)
                .filter(i -> i.getCartId().equals(cart.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", itemId));
        cartItemRepository.delete(item);
    }

    @Transactional(readOnly = true)
    public List<CartItem> getActiveCartItems(UUID consumerId) {
        return cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .map(cart -> cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .orElse(List.of());
    }

    // ------------------------------------------------------------------
    // The checkout slice (the active cart is the checkout aggregate)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CheckoutView findCheckout(UUID cartId, Authentication authentication) {
        return checkoutView(requireOwnedActiveCart(cartId, authentication));
    }

    @Override
    @Transactional
    public CheckoutView addLines(UUID cartId, List<LineAdd> lines, Authentication authentication) {
        Cart cart = requireOwnedActiveCart(cartId, authentication);
        List<LineAdd> requested = lines == null ? List.of() : lines;
        for (LineAdd line : requested) {
            requireLine(line);
            ProductPricingPort.VariantPrice price =
                    productPricingPort.priceOfVariant(line.variantId());
            addCartItem(cart.getConsumerId(), price.productId(), line.variantId(), line.quantity());
        }
        return checkoutView(cart);
    }

    @Override
    @Transactional
    public CheckoutView updateLines(UUID cartId, List<LineUpdate> lines,
                                    Authentication authentication) {
        Cart cart = requireOwnedActiveCart(cartId, authentication);
        List<LineUpdate> requested = lines == null ? List.of() : lines;
        for (LineUpdate update : requested) {
            requireLine(update);
            CartItem item = requireCheckoutLine(cart, update);
            if (update.variantId() != null && !update.variantId().equals(item.getVariantId())) {
                throw new BadRequestException("Line " + item.getId()
                        + " does not reference variant " + update.variantId());
            }
            item.updateQuantity(update.quantity());
        }
        return checkoutView(cart);
    }

    @Override
    @Transactional
    public CheckoutView deleteLines(UUID cartId, List<UUID> lineIds,
                                    Authentication authentication) {
        Cart cart = requireOwnedActiveCart(cartId, authentication);
        List<UUID> requested = lineIds == null ? List.of() : lineIds;
        for (UUID lineId : requested) {
            if (lineId == null) {
                throw new BadRequestException("A checkout line ID is required");
            }
            CartItem item = cartItemRepository.findById(lineId)
                    .filter(candidate -> candidate.getCartId().equals(cart.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("CheckoutLine", lineId));
            cartItemRepository.delete(item);
        }
        return checkoutView(cart);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void deleteAsAdmin(UUID cartId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout", cartId));
        cartItemRepository.deleteAll(
                cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()));
        cartRepository.delete(cart);
    }

    private Cart requireOwnedActiveCart(UUID cartId, Authentication authentication) {
        if (cartId == null) {
            throw new BadRequestException("A checkout ID is required");
        }
        Cart cart = cartRepository.findById(cartId)
                .filter(candidate -> candidate.getStatus() == CartStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout", cartId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!cart.getConsumerId().equals(caller)
                && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Checkout", cartId);
        }
        return cart;
    }

    private CheckoutView checkoutView(Cart cart) {
        List<CartItem> items =
                cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId());
        List<CheckoutView.Line> lines = items.stream()
                .map(item -> {
                    if (item.getVariantId() == null) {
                        throw new ConflictException("Checkout " + cart.getId()
                                + " contains a product-level line; a checkout line must reference "
                                + "a product variant");
                    }
                    return new CheckoutView.Line(item.getId(), item.getVariantId(),
                            item.getQuantity());
                })
                .toList();
        int quantity = items.stream().mapToInt(CartItem::getQuantity).sum();
        return new CheckoutView(cart.getId(), lines, quantity);
    }

    private void requireLine(LineAdd line) {
        if (line == null || line.variantId() == null) {
            throw new BadRequestException("A product variant is required for every checkout line");
        }
        requireQuantity(line.quantity());
    }

    private void requireLine(LineUpdate line) {
        if (line == null) {
            throw new BadRequestException("A checkout line update is required");
        }
        if (line.lineId() == null && line.variantId() == null) {
            throw new BadRequestException("A checkout line ID or variant ID is required");
        }
        if (line.quantity() == null) {
            throw new BadRequestException("A checkout line quantity is required");
        }
        requireQuantity(line.quantity());
    }

    private void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_LINE_QUANTITY) {
            throw new BadRequestException("A checkout line quantity must be between 1 and "
                    + MAX_LINE_QUANTITY);
        }
    }

    private CartItem requireCheckoutLine(Cart cart, LineUpdate update) {
        CartItem item = update.lineId() != null
                ? cartItemRepository.findById(update.lineId())
                        .filter(candidate -> candidate.getCartId().equals(cart.getId()))
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "CheckoutLine", update.lineId()))
                : cartItemRepository.findByCartIdAndVariantId(cart.getId(), update.variantId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "CheckoutLine for variant", update.variantId()));
        if (item.getVariantId() == null) {
            throw new ConflictException("Checkout " + cart.getId()
                    + " contains a product-level line; a checkout line must reference a variant");
        }
        return item;
    }

    // ------------------------------------------------------------------
    // The machine (place -> confirm -> fulfill; cancel from the open states)
    // ------------------------------------------------------------------

    /**
     * Cart to order(S) — the Phase-3 shape (no fulfillment selection):
     * delegates with no voucher and no method (the legacy contract).
     */
    @Transactional
    @Observed(name = "order.place")
    public List<OrderDetail> place(UUID consumerId) {
        return place(consumerId, null, null);
    }
    @Transactional
    @Observed(name = "order.place")
    public List<OrderDetail> place(UUID consumerId, String voucherCode) {
        return place(consumerId, voucherCode, null);
    }

    /**
     * Cart to order(S): the placement transaction — the order row(S), every
     * snapshot line, the frozen COMMERCIAL BREAKDOWN, the frozen FULFILLMENT
     * SELECTION, and the cart's CHECKED_OUT tombstone commit together, or
     * nothing does.
     *
     * <p><b>The V224 checkout selection (the architecture §3.2 Mode B):</b>
     * a PLATFORM_DELIVERY selection resolves EVERY seller group's fare
     * through {@link com.marketplace.shared.api.CheckoutDeliveryPort}
     * INSIDE this transaction — the fee is a synchronous prerequisite of
     * the agreement (exactly like stock), an unserviceable group refuses
     * the whole placement with the honest 409, and the frozen amount joins
     * the order's total (its ONE payment intent carries it — the delivery
     * legs created after confirmation never charge again). PICKUP freezes
     * the method with no destination and no fee. A null selection keeps
     * the legacy shape (the #604 manual add-on path stays available for
     * exactly these orders).
     */
    @Transactional
    @Observed(name = "order.place")
    public List<OrderDetail> place(UUID consumerId, String voucherCode,
                                   FulfillmentSelection fulfillment) {
        if (fulfillment != null && fulfillment.method() == FulfillmentMethod.PLATFORM_DELIVERY) {
            fulfillment.requireDestination();
        }
        Cart cart = cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException(
                        "No active cart to place: the buyer's draft is empty"));
        // The lines read in their TOTAL ORDER (the R4 pattern): the
        // grouping's first entry — and with it the response's PRIMARY
        // order — is the earliest-added line's seller, deterministically,
        // never whichever way a planner chose to scan the rows.
        List<CartItem> lines = cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId());
        if (lines.isEmpty()) {
            throw new ConflictException("Cannot place an order from an empty cart");
        }
        // The Phase-3 re-pricing walk: the pricing policy layer resolves
        // each line's EFFECTIVE unit (the catalog's own record — identity,
        // both shelf states, base price — with an active price-list entry
        // OVERRIDING the amount). The reserved maps split the same way, so
        // the whole-cart all-or-nothing reservation spans BOTH the product
        // shelves and the variant shelves (a refusal of either rolls the
        // WHOLE placement back — no order row in ANY group, no partial hold).
        java.util.Map<UUID, List<CartItem>> groups = new java.util.LinkedHashMap<>();
        java.util.List<ProductStockPort.StockLine> productStockLines = new java.util.ArrayList<>();
        java.util.List<ProductStockPort.StockLine> variantStockLines = new java.util.ArrayList<>();
        for (CartItem line : lines) {
            final UUID sellerId;
            final String effectiveCurrency;
            final long effectivePriceMinor;
            CommercialPricingPort.ResolvedUnitPrice resolved =
                    commercialPricingPort.resolveUnitPrice(line.getProductId(), line.getVariantId());
            if (!resolved.purchasable()) {
                throw new ConflictException("Cart line " + line.getId() + " is no longer "
                        + "purchasable — " + resolved.refusal());
            }
            sellerId = resolved.providerId();
            effectiveCurrency = resolved.currency();
            effectivePriceMinor = resolved.priceMinor();
            if (line.getVariantId() != null) {
                variantStockLines.add(new ProductStockPort.StockLine(
                        line.getVariantId(), line.getQuantity()));
            } else {
                productStockLines.add(new ProductStockPort.StockLine(
                        line.getProductId(), line.getQuantity()));
            }
            if (!line.getCurrency().equals(effectiveCurrency)) {
                throw new ConflictException("Cart line " + line.getId() + " carries "
                        + line.getCurrency() + " but the purchasable unit now prices in "
                        + effectiveCurrency + " — re-add the line before placing");
            }
            if (effectivePriceMinor != line.getUnitAmountMinor()) {
                line.updateUnitAmountMinor(effectivePriceMinor);
            }
            groups.computeIfAbsent(sellerId, k -> new java.util.ArrayList<>()).add(line);
        }
        // One currency per order: a seller group whose re-priced lines
        // carry mixed currencies cannot freeze into one order — the honest
        // 409 (the ADR-0002 stale-currency contract, per group).
        for (java.util.Map.Entry<UUID, List<CartItem>> group : groups.entrySet()) {
            String first = group.getValue().get(0).getCurrency();
            boolean mixed = group.getValue().stream().anyMatch(line -> !first.equals(line.getCurrency()));
            if (mixed) {
                throw new ConflictException("Seller " + group.getKey() + " lines carry mixed currencies"
                        + " — one currency per order (re-add the lines before placing)");
            }
        }
        // The V224 fare walk — BEFORE any stock moves (the fee is a
        // synchronous prerequisite of the agreement, exactly like the
        // price walk above it): every seller group resolves its own
        // platform-delivery fee inside this transaction; an unserviceable
        // group refuses the whole placement with the stock untouched.
        java.util.Map<UUID, Long> shippingBySeller = new java.util.LinkedHashMap<>();
        if (fulfillment != null && fulfillment.method() == FulfillmentMethod.PLATFORM_DELIVERY) {
            for (UUID sellerId : groups.keySet()) {
                var option = checkoutDeliveryPort.fareFor(sellerId,
                        fulfillment.destinationLatitude(), fulfillment.destinationLongitude());
                if (!option.serviceable()) {
                    throw new ConflictException("Seller " + sellerId + " has no platform "
                            + "delivery to this destination — " + option.refusal()
                            + " (remove the seller's lines or choose pickup)");
                }
                String groupCurrency = groups.get(sellerId).get(0).getCurrency();
                if (!groupCurrency.equalsIgnoreCase(option.currency())) {
                    throw new ConflictException("Delivery fare is configured in "
                            + option.currency() + " but the order prices in " + groupCurrency
                            + " — delivery cannot join this order's agreement");
                }
                shippingBySeller.put(sellerId, option.feeMinor());
            }
        }
        // The whole-cart atomic reservation — a zero row anywhere answers
        // 409 and rolls the WHOLE placement back (no order row in ANY
        // group, no partial hold). ADR-0012: the reservation spans BOTH
        // the product-level lines and the variant lines — two calls, ONE
        // transaction, so the all-or-nothing law crosses the shelves.
        if (!productStockLines.isEmpty()) {
            productStockPort.reserve(ProductStockPort.StockLine.asMap(productStockLines));
        }
        if (!variantStockLines.isEmpty()) {
            productStockPort.reserveVariants(ProductStockPort.StockLine.asMap(variantStockLines));
        }
        List<OrderDetail> placed = new java.util.ArrayList<>(groups.size());
        boolean primaryGroup = true;
        for (java.util.Map.Entry<UUID, List<CartItem>> group : groups.entrySet()) {
            UUID sellerId = group.getKey();
            List<CartItem> groupLines = group.getValue();
            long subtotal = 0;
            for (CartItem line : groupLines) {
                subtotal += line.lineTotalMinor();
            }
            String groupCurrency = groupLines.get(0).getCurrency();
            // The Phase-3 breakdown: the pricing module's explicit policies
            // computed for THIS order and FROZEN here — promotion (best
            // single applicable), the ONE voucher (the PRIMARY order only:
            // a placement redeems its voucher exactly once, never per
            // sibling — the single-redemption law), and the jurisdiction's
            // tax component. computeBreakdown is a read; the voucher's
            // redemption (the locked write) follows the order row below.
            String groupVoucher = primaryGroup ? voucherCode : null;
            CommercialPricingPort.CommercialBreakdown breakdown = commercialPricingPort
                    .computeBreakdown(consumerId, subtotal, groupCurrency, groupVoucher);
            long shippingMinor = shippingBySeller.getOrDefault(sellerId, 0L);
            Order order = orderRepository.save(Order.placedWithFulfillment(
                    consumerId, groupCurrency, sellerId,
                    breakdown.subtotalMinor(),
                    breakdown.promotionDiscountMinor() + breakdown.voucherDiscountMinor(),
                    breakdown.taxMinor(), breakdown.taxInclusive(),
                    fulfillment == null ? null : fulfillment.method(),
                    fulfillment == null ? null : fulfillment.destinationAddress(),
                    fulfillment == null ? null : fulfillment.destinationLatitude(),
                    fulfillment == null ? null : fulfillment.destinationLongitude(),
                    shippingMinor));
            freezePriceComponents(order, breakdown);
            // The voucher settlement — INSIDE this placement transaction:
            // the redemption re-validates under the voucher row's
            // pessimistic lock and a refusal rolls the WHOLE placement back
            // (no order row in ANY group survives an over-limit voucher).
            if (groupVoucher != null && !groupVoucher.isBlank()) {
                commercialPricingPort.settleVoucher(order.getId(), consumerId, groupVoucher,
                        breakdown.voucherDiscountMinor(), groupCurrency);
            }
            List<OrderItem> frozen = new java.util.ArrayList<>(groupLines.size());
            for (CartItem line : groupLines) {
                frozen.add(orderItemRepository.save(OrderItem.snapshotOf(order.getId(), line)));
            }
            order.markStockReserved();
            placed.add(new OrderDetail(order, List.copyOf(frozen)));
            primaryGroup = false;
        }
        cart.checkOut(Instant.now());
        cartRepository.save(cart);
        // PLACED publishes nothing — the A-03 discipline (no event without a
        // listener); the machine's cross-boundary information begins at
        // CONFIRMED (and the cancellation edge's OrderCancelledEvent).
        return List.copyOf(placed);
    }

    /**
     * The Phase-3 freeze: the breakdown's itemized components written once
     * beside their order (the study's §3.4 auditable monetary breakdown —
     * every rule that touched the agreement leaves its reference and its
     * amount). Zero-amount components are still recorded (the explicit
     * absence of a rule is itself an auditable fact: an INCLUSIVE 0% tax
     * policy froze the tax at zero).
     */
    private void freezePriceComponents(Order order,
                                       CommercialPricingPort.CommercialBreakdown breakdown) {
        String currency = order.getCurrency();
        if (breakdown.promotionId() != null) {
            orderPriceComponentRepository.save(OrderPriceComponent.promotionOf(
                    order.getId(), breakdown.promotionId(), breakdown.promotionName(),
                    breakdown.promotionDiscountMinor(), currency));
        }
        if (breakdown.voucherId() != null) {
            orderPriceComponentRepository.save(OrderPriceComponent.voucherOf(
                    order.getId(), breakdown.voucherId(), breakdown.voucherCode(),
                    breakdown.voucherDiscountMinor(), currency));
        }
        if (breakdown.taxCountry() != null) {
            orderPriceComponentRepository.save(OrderPriceComponent.taxOf(
                    order.getId(), breakdown.taxCountry(),
                    breakdown.taxInclusive() ? "INCLUSIVE" : "EXCLUSIVE",
                    breakdown.taxRateBps(), breakdown.taxMinor(), currency));
        }
    }

    /**
     * PLACED → CONFIRMED. The merchant-side acceptance surface (the store's
     * merchant console arrives with C.7/M1 — the machine and its guard are
     * the load-bearing machinery today, exercised by the journey gate).
     */
    @Transactional
    @Observed(name = "order.confirm")
    public Order confirm(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        requireStatus(order, OrderStatus.PLACED, "confirm");
        order.confirm(Instant.now());
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderConfirmedEvent(order.getId(), order.getConsumerId()));
        return saved;
    }

    /**
     * CONFIRMED → FULFILLED (terminal). The delivery completion — and the
     * stock's commit edge (ADR-0002): the reservation becomes a real
     * deduction, exactly once (the machine's guard is the idempotency).
     *
     * <p><b>The V224 multi-leg law:</b> a leg's DELIVERED event fulfills
     * the order only when it was the LAST active leg — an order split
     * into several packages fulfills when EVERY package reaches its
     * terminal state; a completing non-final leg is a logged no-op (the
     * event's redelivery stays safe by the same state guard).
     */
    @Override
    @Transactional
    @Observed(name = "order.fulfill.delivery")
    public void fulfillFromDelivery(UUID orderId, UUID deliveryRequestId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        // The multi-leg law's completion edge (the review's Major finding,
        // root-caused): on a checkout-selected platform delivery ANY leg
        // of the order may be the last one — split legs never ride the
        // order's primary link, and a cancelled primary clears it, so the
        // link equality only rules the manual #604 path (where the leg IS
        // the order's one delivery). The idempotency follows the same
        // law: a redelivered event for ANY leg of a fulfilled platform
        // order is the no-op it always was for the primary.
        boolean platformDelivery =
                order.getFulfillmentMethod() == FulfillmentMethod.PLATFORM_DELIVERY;
        if (order.getStatus() == OrderStatus.FULFILLED
                && (platformDelivery || deliveryRequestId.equals(order.getDeliveryRequestId()))) {
            return;
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("Delivery completion does not match the confirmed linked order");
        }
        // A leg completing beside still-active siblings: the order waits —
        // the multi-leg law.
        if (platformDelivery) {
            if (checkoutDeliveryPort.remainingActiveLegs(orderId) > 0) {
                return;
            }
        } else if (!deliveryRequestId.equals(order.getDeliveryRequestId())) {
            throw new ConflictException("Delivery completion does not match the confirmed linked order");
        }
        Instant at = Instant.now();
        order.fulfill(at);
        if (order.isStockReserved()) {
            productStockPort.commit(productStockMapOf(order.getId()));
            productStockPort.commitVariants(variantStockMapOf(order.getId()));
            order.markStockReleased();
        }
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFulfilledEvent(order.getId(), order.getConsumerId()));
    }

    /**
     * CodeRabbit agent findings, row 16 — the aggregate-fulfillment
     * reconciliation: a leg reached a terminal state WITHOUT physical
     * delivery (CANCELLED or FAILED — {@code DeliveryLegTerminatedEvent}),
     * and no DELIVERED event will ever fire for it. The multi-leg law's
     * completion edge only rides DELIVERED, so an order whose LAST active
     * leg terminates after another leg was already delivered would wait
     * forever in CONFIRMED — the stuck state the delivered-goods
     * cancellation guard now refuses to let the buyer escape. This
     * reconciliation reads the aggregate truth and completes the order
     * through the SAME verified edge when the delivery completed in fact:
     * every leg terminal AND at least one delivered.
     *
     * <p>Every other aggregate state is an honest logged no-op (the
     * event's redelivery must never surface as an error — the Modulith
     * resubmission contract): a non-platform order, an order that is not
     * CONFIRMED (already fulfilled/cancelled), legs still in flight, or
     * nothing delivered at all (the all-cancelled aggregate stays
     * cancellable through the existing surface).
     */
    @Transactional
    @Observed(name = "order.fulfill.delivery.reconcile")
    public void reconcileDeliveryTermination(UUID orderId, UUID deliveryRequestId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.info("Delivery termination reconciliation no-op: unknown order {} (leg {})",
                    orderId, deliveryRequestId);
            return;
        }
        if (order.getStatus() != OrderStatus.CONFIRMED
                || order.getFulfillmentMethod() != FulfillmentMethod.PLATFORM_DELIVERY) {
            log.info("Delivery termination reconciliation no-op: order {} is {} via {} — "
                            + "only a confirmed platform-delivery order reconciles (leg {})",
                    orderId, order.getStatus(), order.getFulfillmentMethod(), deliveryRequestId);
            return;
        }
        if (checkoutDeliveryPort.remainingActiveLegs(orderId) > 0) {
            log.info("Delivery termination reconciliation no-op: order {} still has active "
                            + "legs (leg {} terminated)", orderId, deliveryRequestId);
            return;
        }
        if (checkoutDeliveryPort.deliveredLegs(orderId) == 0) {
            log.info("Delivery termination reconciliation no-op: order {} has no delivered "
                            + "legs — the aggregate stays cancellable (leg {})", orderId, deliveryRequestId);
            return;
        }
        log.info("Delivery termination reconciliation: order {} completed in fact (leg {} "
                        + "terminated, every leg terminal, at least one delivered) — fulfilling",
                orderId, deliveryRequestId);
        fulfillFromDelivery(orderId, deliveryRequestId);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDeliveryDetails requireConfirmedOrderForDelivery(UUID orderId, UUID buyerId) {
        Order order = requireOrder(orderId);
        if (!order.getConsumerId().equals(buyerId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getSellerId() == null) {
            throw new ConflictException("Only a confirmed order with a known seller can be delivered");
        }
        return new OrderDeliveryDetails(order.getId(), order.getConsumerId(), order.getSellerId(),
                order.getCurrency(), order.getDeliveryRequestId());
    }

    /**
     * The V224 checkout-integration read: the CONFIRMED order's frozen
     * fulfillment selection — the delivery module's leg-creation listener
     * and the manual add-on guard consume it. The legacy shape (a NULL
     * method) answers the selection with a null method — the caller
     * decides what that means (the manual quoting path stays open for it).
     */
    @Override
    @Transactional(readOnly = true)
    public com.marketplace.shared.api.OrderFulfillmentSelection requireOrderFulfillment(
            UUID orderId, UUID buyerId) {
        return selectionOf(requireOrder(orderId), orderId, buyerId);
    }

    /**
     * Part-2 review round 2 (the full review's measured Major): the SAME
     * frozen selection, read under the order row's write lock — the
     * leg-creation surfaces ({@code DeliveryService.addLegForOrder} and
     * the {@code OrderFulfillmentListener}) serialize with the direct
     * fulfillment gate on the order row itself. The finding's exact
     * interleaving: {@code fulfill} locks the order, counts ZERO active
     * legs and commits — while the split path, reading the order WITHOUT
     * the lock, still saw CONFIRMED and created a live leg after the
     * count and before the commit: an order FULFILLED with a package
     * still in flight. Reading through this method, the leg-creation
     * transaction BLOCKS on the same row lock {@code fulfill} holds,
     * then reads the committed truth (a moved-past order answers the
     * honest 409/404) — the legs and the fulfillment gate are one
     * serialized surface now. The manual quoting path keeps the
     * lock-free read above: a quote creates no leg, and the
     * read-only transaction stays read-only.
     */
    @Override
    @Transactional
    public com.marketplace.shared.api.OrderFulfillmentSelection requireOrderFulfillmentForUpdate(
            UUID orderId, UUID buyerId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return selectionOf(order, orderId, buyerId);
    }

    /** The frozen selection's one builder — both reads answer the same facts. */
    private com.marketplace.shared.api.OrderFulfillmentSelection selectionOf(
            Order order, UUID orderId, UUID buyerId) {
        if (!order.getConsumerId().equals(buyerId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getSellerId() == null) {
            throw new ConflictException("Only a confirmed order with a known seller can be delivered");
        }
        return new com.marketplace.shared.api.OrderFulfillmentSelection(
                order.getId(), order.getConsumerId(), order.getSellerId(), order.getCurrency(),
                order.getFulfillmentMethod() == null ? null : order.getFulfillmentMethod().name(),
                order.getShipToAddress(), order.getShipToLatitude(), order.getShipToLongitude(),
                order.getShippingAmountMinor() == null ? 0 : order.getShippingAmountMinor(),
                order.getDeliveryRequestId());
    }

    /**
     * Agent findings 2026-10-26T13:11 export, row 41 — the settlement
     * edge's resolution. The filter pre-guards the SAME two conditions
     * {@link #requireOrderFulfillment} throws on (CONFIRMED + a known
     * seller), so the read answers the selection or the honest absent —
     * an exception is never the events consumer's answer. A seller-first
     * confirmed order still PLACED-confirmed-but-unpaid is present here
     * (CONFIRMED), and its leg is created by THIS edge the moment the
     * payment settles — the confirmation edge alone stopped creating
     * legs on the seller's bare word.
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<com.marketplace.shared.api.OrderFulfillmentSelection>
    findOrderFulfillmentByPaymentIntent(UUID paymentIntentId) {
        return orderRepository.findByPaymentIntentId(paymentIntentId)
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED && order.getSellerId() != null)
                .map(order -> requireOrderFulfillment(order.getId(), order.getConsumerId()));
    }

    @Override
    @Transactional
    @Observed(name = "order.delivery.attach")
    public void attachDeliveryRequest(UUID orderId, UUID buyerId, UUID deliveryRequestId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (!order.getConsumerId().equals(buyerId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("Only a confirmed order can be linked to delivery");
        }
        if (order.getDeliveryRequestId() != null
                && !order.getDeliveryRequestId().equals(deliveryRequestId)) {
            throw new ConflictException("Order already has a delivery request");
        }
        order.attachDeliveryRequest(deliveryRequestId);
        orderRepository.save(order);
    }

    @Override
    @Transactional
    @Observed(name = "order.delivery.clear")
    public void clearDeliveryRequest(UUID orderId, UUID deliveryRequestId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return;
        order.clearDeliveryRequest(deliveryRequestId);
        orderRepository.save(order);
    }

    /**
     * CONFIRMED → FULFILLED (terminal) — the direct fulfillment surface
     * (the merchant console's completion lever rides it through
     * {@link #fulfillForSeller}).
     *
     * <p><b>The delivery gates, in law order:</b> (1) a manually linked
     * delivery owns its order's completion while the link stands — the
     * primary-link refusal below; (2) CodeRabbit agent findings, row 12 —
     * a checkout-selected platform delivery owns completion through its
     * VERIFIED lifecycle regardless of the primary link, because the
     * buyer's own cancellation clears that link
     * ({@code DeliveryService.cancelForRequester} →
     * {@link #clearDeliveryRequest}) while the split aggregate keeps
     * flying: {@link #requireDirectFulfillableAggregate} reads the same
     * aggregate truth the cancellation guard and the multi-leg law read,
     * BEFORE any supported direct fulfillment changes the order or
     * commits inventory.
     */
    @Transactional
    @Observed(name = "order.fulfill")
    public Order fulfill(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() == OrderStatus.CONFIRMED && order.getDeliveryRequestId() != null) {
            throw new ConflictException("An order linked to an active delivery must be fulfilled through its delivery lifecycle");
        }
        requireStatus(order, OrderStatus.CONFIRMED, "fulfill");
        requireDirectFulfillableAggregate(order);
        order.fulfill(Instant.now());
        if (order.isStockReserved()) {
            productStockPort.commit(productStockMapOf(order.getId()));
            productStockPort.commitVariants(variantStockMapOf(order.getId()));
            order.markStockReleased();
        }
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFulfilledEvent(order.getId(), order.getConsumerId()));
        return saved;
    }

    /**
     * PLACED/CONFIRMED → CANCELLED (terminal). The buyer's own escape hatch
     * (the controller route) and the machine's administrative one share the
     * same guarded edge.
     *
     * <p><b>Stage 6 (ADR-0002):</b> cancellation releases the placement's
     * stock reservation exactly once (the {@code stockReserved} flag), and
     * the money settles through the EXISTING engine — the
     * {@code OrderCancelledEvent} carries the edge to the payments module,
     * whose listener cancels the unpaid intent or fully refunds the
     * collected one (the booking-cancellation pattern verbatim; no refund
     * path is reimplemented here).
     */
    @Transactional
    @Observed(name = "order.cancel")
    public Order cancel(UUID orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        requireCancellableOrder(order);
        order.cancel(reason, Instant.now());
        if (order.isStockReserved()) {
            productStockPort.release(productStockMapOf(order.getId()));
            productStockPort.releaseVariants(variantStockMapOf(order.getId()));
            order.markStockReleased();
        }
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderCancelledEvent(
                order.getId(), order.getConsumerId(), reason));
        return saved;
    }

    /**
     * CodeRabbit agent findings, row 12 — the direct-fulfillment
     * aggregate gate. The only delivery guard {@link #fulfill} carried
     * was the order's PRIMARY delivery link — a link the buyer's own
     * cancellation clears ({@code DeliveryService.cancelForRequester} →
     * {@link #clearDeliveryRequest}) — so after cancelling the primary
     * leg the owning seller could fulfill a split platform-delivery
     * order while its SECONDARY legs were still in flight, bypassing
     * the delivery completion and its proof requirements. The aggregate
     * state is the same truth the cancellation guard and the multi-leg
     * law read ({@code CheckoutDeliveryPort} — "the count the
     * fulfillment and cancellation gates read"):
     *
     * <ul>
     *   <li>any leg still active → the verified delivery lifecycle owns
     *   completion (409 — no direct fulfillment changes the order or
     *   commits stock);</li>
     *   <li>every leg terminal and at least one delivered → the
     *   aggregate completed IN FACT — the exact condition the terminal
     *   reconciliation fulfills under, so the direct fulfillment is
     *   SUPPORTED (the row lock and the state machine make the seller's
     *   edge and the reconciliation edge one);</li>
     *   <li>every leg terminal and nothing delivered → no delivery
     *   ever completed: no direct fulfillment may write terminal state
     *   over undelivered goods (409) — the aggregate stays cancellable
     *   through the existing surface.</li>
     * </ul>
     *
     * <p>Non-platform orders (pickup, legacy, or a manually linked
     * delivery) keep the primary-link guard alone — their one delivery
     * IS the link.
     */
    private void requireDirectFulfillableAggregate(Order order) {
        if (order.getFulfillmentMethod() != FulfillmentMethod.PLATFORM_DELIVERY) {
            return;
        }
        if (checkoutDeliveryPort.remainingActiveLegs(order.getId()) > 0) {
            throw new ConflictException("An order with an in-flight platform delivery cannot be"
                    + " fulfilled directly — it completes through its delivery lifecycle");
        }
        if (checkoutDeliveryPort.deliveredLegs(order.getId()) == 0) {
            throw new ConflictException("A platform-delivery order with no delivered package cannot"
                    + " be fulfilled directly — nothing was delivered; the order stays cancellable");
        }
    }

    /** The shared lifecycle and delivery constraints for cancellation. */
    private void requireCancellableOrder(Order order) {
        if (order.getDeliveryRequestId() != null) {
            throw new ConflictException("An order linked to an active delivery must be cancelled through its delivery lifecycle");
        }
        // A checkout-selected platform delivery cannot be cancelled while
        // any leg is in flight; the primary request link is not sufficient.
        if (order.getFulfillmentMethod() == FulfillmentMethod.PLATFORM_DELIVERY
                && checkoutDeliveryPort.remainingActiveLegs(order.getId()) > 0) {
            throw new ConflictException("An order with an in-flight platform delivery cannot be cancelled — cancel its delivery legs first");
        }
        // CodeRabbit agent findings, row 16 — the delivered-goods guard: a
        // full cancellation releases ALL reserved inventory and requests a
        // FULL refund, so it must refuse whenever any package was already
        // physically delivered (the multi-leg opening: cancel the
        // uncollected primary AFTER a secondary package arrived, obtain the
        // full refund, keep the delivered goods). Only a partial-
        // cancellation operation — a separate future surface — may refund
        // and release the undelivered rest.
        if (order.getFulfillmentMethod() == FulfillmentMethod.PLATFORM_DELIVERY
                && checkoutDeliveryPort.deliveredLegs(order.getId()) > 0) {
            throw new ConflictException("An order with a delivered package cannot be fully cancelled"
                    + " — the delivered goods are already in the buyer's hands; a partial"
                    + " cancellation of the undelivered rest is required");
        }
        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("Order " + order.getId() + " is " + order.getStatus()
                    + " — cancel is legal from PLACED or CONFIRMED only");
        }
    }

    /**
     * Enforce the management boundary before a transport adapter decodes
     * caller-supplied IDs. The method has no domain side effect.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public void requireAdmin() {
    }

    /**
     * Saleor P4 order-confirm management command. Confirmation remains the
     * orders owner's existing state transition and event publication; this
     * adapter does not add a second payment-capture path.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Observed(name = "order.admin.confirm")
    public OrderView confirmAsAdmin(UUID orderId) {
        Order confirmed = confirm(orderId);
        return new OrderView(confirmed.getId(), confirmed.getPlacedAt(), confirmed.getStatus().name());
    }

    /**
     * Saleor's bulk-cancel semantics: inspect every unique order under a
     * row lock before performing transitions. State refusals are item errors;
     * unexpected execution failures escape the enclosing transaction.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Observed(name = "order.admin.bulk-cancel")
    public com.marketplace.orders.spi.OrderBulkCancelResult bulkCancelAsAdmin(List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return new com.marketplace.orders.spi.OrderBulkCancelResult(0, List.of());
        }

        java.util.Set<UUID> uniqueIds = new java.util.LinkedHashSet<>(orderIds);
        java.util.List<com.marketplace.orders.spi.OrderBulkCancelResult.Failure> failures =
                new java.util.ArrayList<>();

        // Stable ordering prevents overlapping bulk requests from acquiring
        // row locks in opposite order and unnecessarily deadlocking.
        List<UUID> orderedIds = uniqueIds.stream().sorted().toList();

        // Match Saleor's clean-input-before-bulk-action behavior: no order is
        // transitioned until all unique IDs have had their locked state and
        // delivery constraints checked.
        java.util.List<UUID> cancellableIds = new java.util.ArrayList<>();
        for (UUID orderId : orderedIds) {
            Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
            if (order == null) {
                failures.add(new com.marketplace.orders.spi.OrderBulkCancelResult.Failure(
                        orderId,
                        com.marketplace.orders.spi.OrderBulkCancelResult.FailureKind.NOT_FOUND,
                        "Order not found: " + orderId));
                continue;
            }
            try {
                requireCancellableOrder(order);
                cancellableIds.add(orderId);
            } catch (ConflictException exception) {
                failures.add(new com.marketplace.orders.spi.OrderBulkCancelResult.Failure(
                        orderId,
                        com.marketplace.orders.spi.OrderBulkCancelResult.FailureKind.CONFLICT,
                        exception.getMessage()));
            }
        }

        int cancelledCount = 0;
        for (UUID orderId : cancellableIds) {
            cancel(orderId, "Cancelled through the order-management bulk API");
            cancelledCount++;
        }
        return new com.marketplace.orders.spi.OrderBulkCancelResult(cancelledCount, failures);
    }

    /**
     * Saleor P4 order-management mutation backed by the existing domain
     * state machine. ADMIN authorization belongs at this service boundary.
     * Cancellation locks and transitions the order, releases its stock
     * reservation, and publishes OrderCancelledEvent so the existing
     * payments owner settles its intent. No PSP or ledger logic is duplicated.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Observed(name = "order.admin.cancel")
    public OrderView cancelAsAdmin(UUID orderId) {
        Order cancelled = cancel(orderId, "Cancelled through the order-management API");
        return new OrderView(cancelled.getId(), cancelled.getPlacedAt(), cancelled.getStatus().name());
    }

    /**
     * Stage 6 (ADR-0002): the buyer's payment-intent surface — creates (or
     * idempotently returns) the order's intent through the EXISTING
     * payments engine ({@link OrderPaymentPort}; one intent per order, the
     * V184 partial-unique index as the second line of defense) and links
     * it to the machine. The total is the frozen lines' sum — the intent
     * carries exactly what the buyer agreed to at placement.
     */
    @Transactional
    @Observed(name = "order.payment-intent")
    public PaymentIntentDetails requestPaymentIntent(UUID orderId, Authentication authentication) {
        Order order = getOrderForUser(orderId, authentication);
        requireStatus(order, OrderStatus.PLACED, "payment-intent creation");
        PaymentIntentDetails details = orderPaymentPort.createForOrder(
                order.getId(), order.getConsumerId(), order.getTotalAmountMinor(), order.getCurrency());
        if (!details.paymentIntentId().equals(order.getPaymentIntentId())) {
            order.linkPaymentIntent(details.paymentIntentId());
            orderRepository.save(order);
        }
        return details;
    }

    /**
     * Stage 6 (ADR-0002): the settlement listener's write — the payment's
     * COMPLETED state auto-confirms the PLACED order (the booking's
     * auto-confirm pattern verbatim). Idempotent by the state guard: a
     * redelivered event for an already-CONFIRMED (or moved-past) order is
     * a logged no-op, never a 409 the retry would trap on.
     */
    @Transactional
    public void confirmFromPayment(java.util.UUID paymentIntentId) {
        // Serialize concurrent/delivered-again payment completion events on
        // the authoritative order row. A redelivery that observes CONFIRMED
        // after the lock is acquired is a no-op, not a stale-state conflict.
        orderRepository.findByPaymentIntentId(paymentIntentId)
                .ifPresent(order -> orderRepository.findByIdForUpdate(order.getId())
                        .filter(locked -> locked.getStatus() == OrderStatus.PLACED)
                        .ifPresent(locked -> confirm(locked.getId())));
    }

    /**
     * The frozen lines' product→quantity map, one hop, for the stock
     * seams — the PRODUCT-level lines only (ADR-0012: the variant lines
     * ride their own map beside it; an empty map is the port's no-op).
     */
    private java.util.Map<UUID, Integer> productStockMapOf(UUID orderId) {
        return ProductStockPort.StockLine.asMap(orderItemRepository.findByOrderId(orderId).stream()
                .filter(item -> item.getVariantId() == null)
                .map(item -> new ProductStockPort.StockLine(item.getProductId(), item.getQuantity()))
                .toList());
    }

    /**
     * ADR-0012 (the cart-integration slice): the frozen lines' VARIANT→quantity
     * map — the variant shelves' own release/commit carrier, the
     * product map's twin one hop away.
     */
    private java.util.Map<UUID, Integer> variantStockMapOf(UUID orderId) {
        return ProductStockPort.StockLine.asMap(orderItemRepository.findByOrderId(orderId).stream()
                .filter(item -> item.getVariantId() != null)
                .map(item -> new ProductStockPort.StockLine(item.getVariantId(), item.getQuantity()))
                .toList());
    }

    // ------------------------------------------------------------------
    // Reads (the privacy contract: the buyer or ADMIN, an honest 404 otherwise)
    // ------------------------------------------------------------------

    /**
     * The Phase-3 checkout preview: the buyer's active cart re-priced and
     * broken down EXACTLY as the placement would freeze it — one breakdown
     * per seller group (the primary group carries the voucher), with NO
     * write anywhere (no stock reservation, no redemption, no tombstone).
     * The buyer sees the commercial agreement before committing; the
     * placement re-computes at ITS instant (the frozen record is the
     * placement-instant agreement, never this preview).
     */
    @Transactional(readOnly = true)
    public List<PlacementPreview> previewPlacement(UUID consumerId, String voucherCode) {
        return previewPlacement(consumerId, voucherCode, null);
    }

    @Transactional(readOnly = true)
    public List<PlacementPreview> previewPlacement(UUID consumerId, String voucherCode,
                                                   FulfillmentSelection fulfillment) {
        // The same destination gate the placement runs BEFORE any fare
        // walk (the review's finding): an incomplete destination answers
        // the honest 400 here too — never the unboxing NPE of a null
        // coordinate reaching fareFor's double parameters as a 500.
        if (fulfillment != null && fulfillment.method() == FulfillmentMethod.PLATFORM_DELIVERY) {
            fulfillment.requireDestination();
        }
        Cart cart = cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("No active cart to preview"));
        List<CartItem> lines = cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId());
        if (lines.isEmpty()) {
            throw new ConflictException("Cannot preview an order from an empty cart");
        }
        java.util.Map<UUID, Long> subtotals = new java.util.LinkedHashMap<>();
        java.util.Map<UUID, String> currencies = new java.util.LinkedHashMap<>();
        for (CartItem line : lines) {
            CommercialPricingPort.ResolvedUnitPrice resolved =
                    commercialPricingPort.resolveUnitPrice(line.getProductId(), line.getVariantId());
            if (!resolved.purchasable()) {
                throw new ConflictException("Cart line " + line.getId() + " is no longer "
                        + "purchasable — " + resolved.refusal());
            }
            subtotals.merge(resolved.providerId(), line.getQuantity() * resolved.priceMinor(),
                    Long::sum);
            currencies.putIfAbsent(resolved.providerId(), resolved.currency());
        }
        boolean primary = true;
        List<PlacementPreview> previews = new java.util.ArrayList<>(subtotals.size());
        for (java.util.Map.Entry<UUID, Long> group : subtotals.entrySet()) {
            String groupVoucher = primary ? voucherCode : null;
            String groupCurrency = currencies.get(group.getKey());
            // The V224 option beside the breakdown: a platform-delivery
            // selection previews EVERY seller group's fare (the honest
            // unserviceable option surfaces here — the buyer sees «no
            // platform delivery» before committing).
            var option = fulfillment != null
                    && fulfillment.method() == FulfillmentMethod.PLATFORM_DELIVERY
                    ? checkoutDeliveryPort.fareFor(group.getKey(),
                            fulfillment.destinationLatitude(), fulfillment.destinationLongitude())
                    : null;
            previews.add(new PlacementPreview(group.getKey(), groupCurrency,
                    commercialPricingPort.computeBreakdown(
                            consumerId, group.getValue(), groupCurrency, groupVoucher),
                    option));
            primary = false;
        }
        return previews;
    }

    /** One seller group's preview — the breakdown the placement would freeze. */
    public record PlacementPreview(UUID sellerId, String currency,
                                   CommercialPricingPort.CommercialBreakdown breakdown,
                                   com.marketplace.shared.api.CheckoutDeliveryPort.DeliveryFareOption deliveryOption) {
    }

    /**
     * The V224 checkout fulfillment selection — the buyer's placement
     * command: the method (PICKUP or PLATFORM_DELIVERY) and, for a platform
     * delivery, the destination snapshot (the address the platform delivers
     * to). The placement resolves every seller group's fare from the
     * destination INSIDE its transaction.
     */
    public record FulfillmentSelection(FulfillmentMethod method,
                                       String destinationAddress,
                                       Double destinationLatitude,
                                       Double destinationLongitude) {

        /** A platform delivery requires the whole destination snapshot. */
        void requireDestination() {
            if (method != FulfillmentMethod.PLATFORM_DELIVERY) {
                return;
            }
            if (destinationAddress == null || destinationAddress.isBlank()
                    || destinationLatitude == null || destinationLongitude == null
                    || destinationLatitude < -90 || destinationLatitude > 90
                    || destinationLongitude < -180 || destinationLongitude > 180) {
                throw new com.marketplace.shared.api.BadRequestException(
                        "A platform-delivery selection requires a complete destination "
                                + "(address, latitude, longitude)");
            }
        }
    }

    @Transactional(readOnly = true)
    public OrderDetail getOrderDetailForUser(UUID orderId, Authentication authentication) {
        Order order = requireOrder(orderId);
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!order.getConsumerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        return new OrderDetail(order, orderItemRepository.findByOrderId(orderId));
    }

    /**
     * The buyer's cancel surface: privacy gate + the guarded edge + the
     * view assembly, chained for the HTTP boundary.
     */
    @Transactional
    public OrderDetail cancelForUser(UUID orderId, String reason, Authentication authentication) {
        Order order = getOrderForUser(orderId, authentication);
        Order cancelled = cancel(order.getId(), reason);
        return new OrderDetail(cancelled, orderItemRepository.findByOrderId(orderId));
    }

    @Transactional(readOnly = true)
    public Order getOrderForUser(UUID orderId, Authentication authentication) {
        Order order = requireOrder(orderId);
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!order.getConsumerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        return order;
    }

    @Transactional(readOnly = true)
    public List<OrderItem> getOrderItems(UUID orderId) {
        return orderItemRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public OrderDetail getOrderDetail(UUID orderId) {
        return new OrderDetail(requireOrder(orderId), orderItemRepository.findByOrderId(orderId));
    }

    @Transactional(readOnly = true)
    public Page<OrderDetail> listByConsumer(UUID consumerId, Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!consumerId.equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Order");
        }
        Page<Order> page = orderRepository.findByConsumerIdOrderByPlacedAtDescIdDesc(consumerId, pageable);
        if (page.isEmpty()) {
            return page.map(order -> new OrderDetail(order, List.of()));
        }
        // ONE items hop for the whole page, grouped per order — no N+1.
        java.util.Map<UUID, List<OrderItem>> byOrder = orderItemRepository
                .findByOrderIdIn(page.getContent().stream().map(Order::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItem::getOrderId));
        return page.map(order -> new OrderDetail(order,
                byOrder.getOrDefault(order.getId(), List.of())));
    }

    // ------------------------------------------------------------------
    // The merchant surface (the Saleor study's Phase-1 slice, PR #602 —
    // the C.7/M1 A-17 promise the buyer surface documented on day one)
    // ------------------------------------------------------------------

    /**
     * The seller's order detail: the same frozen snapshot the buyer reads,
     * scoped to the order's own seller (or ADMIN). The privacy contract is
     * the module's law verbatim — an honest 404 to anyone else, because
     * existence itself is private (the A-03 precedent; the buyer's
     * {@code getOrderDetailForUser} twin).
     */
    @Transactional(readOnly = true)
    public OrderDetail getOrderDetailForSeller(UUID orderId, Authentication authentication) {
        Order order = requireSellerOrder(orderId, authentication);
        return new OrderDetail(order, orderItemRepository.findByOrderId(orderId));
    }

    /**
     * The seller's sold-order history: the caller's own sales, newest-first
     * — the buyer's {@code listByConsumer(caller, ...)} twin. A caller with
     * no sales reads an honest empty page (a user who never sold carries
     * no rows — the same answer {@code listMine} gives a buyer without
     * orders).
     */
    @Transactional(readOnly = true)
    public Page<OrderDetail> listSoldByCaller(Authentication authentication, Pageable pageable) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        Page<Order> page = orderRepository.findBySellerIdOrderByPlacedAtDescIdDesc(caller, pageable);
        if (page.isEmpty()) {
            return page.map(order -> new OrderDetail(order, List.of()));
        }
        // ONE items hop for the whole page, grouped per order — no N+1
        // (the listByConsumer assembly verbatim).
        java.util.Map<UUID, List<OrderItem>> byOrder = orderItemRepository
                .findByOrderIdIn(page.getContent().stream().map(Order::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItem::getOrderId));
        return page.map(order -> new OrderDetail(order,
                byOrder.getOrDefault(order.getId(), List.of())));
    }

    /**
     * The merchant's acceptance edge: PLACED → CONFIRMED, guarded by the
     * seller scope, answering the machine's new state as the read-back
     * (the command's own body is the proof — the study's "post-command
     * read-back" requirement). The machine's {@link #confirm} edge and its
     * event stay the single load-bearing machinery.
     */
    @Transactional
    @Observed(name = "order.seller.confirm")
    public OrderDetail confirmForSeller(UUID orderId, Authentication authentication) {
        requireSellerOrder(orderId, authentication);
        Order confirmed = confirm(orderId);
        return new OrderDetail(confirmed, orderItemRepository.findByOrderId(orderId));
    }

    /**
     * The merchant's completion edge: CONFIRMED → FULFILLED (terminal) —
     * the stock's commit edge (ADR-0002) rides the machine's own
     * {@link #fulfill}; the guards are the seller scope and the
     * direct-fulfillment aggregate gate (CodeRabbit agent findings,
     * row 12 — a platform-delivery order completes through its verified
     * delivery lifecycle, never over in-flight or undelivered legs),
     * and the read-back is the command's own body.
     */
    @Transactional
    @Observed(name = "order.seller.fulfill")
    public OrderDetail fulfillForSeller(UUID orderId, Authentication authentication) {
        requireSellerOrder(orderId, authentication);
        Order fulfilled = fulfill(orderId);
        return new OrderDetail(fulfilled, orderItemRepository.findByOrderId(orderId));
    }

    /**
     * The merchant's administrative cancel: the same guarded machine edge
     * the buyer's escape hatch rides (PLACED/CONFIRMED → CANCELLED, stock
     * released once, money settled through the existing engine's
     * {@code OrderCancelledEvent}) — one edge, two authorized callers, the
     * seller scope deciding who.
     */
    @Transactional
    @Observed(name = "order.seller.cancel")
    public OrderDetail cancelForSeller(UUID orderId, String reason, Authentication authentication) {
        requireSellerOrder(orderId, authentication);
        Order cancelled = cancel(orderId, reason);
        return new OrderDetail(cancelled, orderItemRepository.findByOrderId(orderId));
    }

    /**
     * The seller-scope gate (the A1 convention — the
     * {@code verifyProviderOwnership} pattern the booking and reverse-review
     * surfaces established): {@code orders.seller_id} physically carries the
     * provider's {@code users.id} (the placement copied catalog's
     * {@code Product.providerId} — the A1 space), so the ruling is read
     * from the row itself and compared directly against the caller's user
     * id — NO profile resolution. A legacy order with no seller (V184:
     * {@code seller_id} is nullable for rows that predate the invariant)
     * has no seller to authorize: ADMIN only. Anyone else reads the
     * module's honest 404 — existence itself is private.
     */
    private Order requireSellerOrder(UUID orderId, Authentication authentication) {
        Order order = requireOrder(orderId);
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        boolean seller = order.getSellerId() != null && order.getSellerId().equals(caller);
        if (!seller && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        return order;
    }

    private Order requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private void requireStatus(Order order, OrderStatus expected, String transition) {
        if (order.getStatus() != expected) {
            throw new ConflictException("Order " + order.getId() + " is " + order.getStatus()
                    + " — " + transition + " is legal from " + expected + " only");
        }
    }
}
