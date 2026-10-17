package com.marketplace.orders;

import com.marketplace.shared.api.BadRequestException;
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
public class OrdersService implements OrderDeliveryPort {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductPricingPort productPricingPort;
    private final ProductStockPort productStockPort;
    private final OrderPaymentPort orderPaymentPort;

    public OrdersService(CartRepository cartRepository,
                         CartItemRepository cartItemRepository,
                         OrderRepository orderRepository,
                         OrderItemRepository orderItemRepository,
                         CurrentUserProvider currentUserProvider,
                         ApplicationEventPublisher eventPublisher,
                         ProductPricingPort productPricingPort,
                         ProductStockPort productStockPort,
                         OrderPaymentPort orderPaymentPort) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.productPricingPort = productPricingPort;
        this.productStockPort = productStockPort;
        this.orderPaymentPort = orderPaymentPort;
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
                .map(cart -> cartItemRepository.findByCartId(cart.getId()))
                .orElse(List.of());
    }

    // ------------------------------------------------------------------
    // The machine (place -> confirm -> fulfill; cancel from the open states)
    // ------------------------------------------------------------------

    /**
     * Cart to order(S): the placement transaction — the order row(S), every
     * snapshot line, and the cart's CHECKED_OUT tombstone commit together,
     * or nothing does. The total is derived (never caller-supplied): the
     * sum of the frozen lines.
     *
     * <p><b>Stage 6 (ADR-0002):</b> placement (1) re-resolves every line's
     * authoritative price and state — a product that left the ACTIVE shelf
     * or changed its price since the add re-prices the line here (the
     * frozen record is the placement-instant agreement), (2) reserves the
     * lines' stock through {@link ProductStockPort} — the whole-map atomic
     * write whose refusal rolls the whole placement back.
     *
     * <p><b>ADR-0010 (the ADR-0002 deferral opened — the multi-seller
     * split):</b> the placement groups the cart by its sellers (first-seen
     * order preserved) and creates ONE ORDER PER SELLER — each with its own
     * derived total, its own single currency (a group carrying mixed
     * currencies answers the honest 409), its own frozen lines, and its
     * own payment/ledger settlement (the {@code orders.seller_id}
     * single-value invariant stands per order). The reserve stays ONE
     * all-or-nothing whole-cart call, the cart tombstones ONCE, and the
     * machine's PLACED-no-event discipline is untouched. Returns the views
     * in the groups' first-seen order (the first order is the placement's
     * primary; the wire carries the rest in {@code additionalOrders}).
     */
    @Transactional
    @Observed(name = "order.place")
    public List<OrderDetail> place(UUID consumerId) {
        Cart cart = cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException(
                        "No active cart to place: the buyer's draft is empty"));
        List<CartItem> lines = cartItemRepository.findByCartId(cart.getId());
        if (lines.isEmpty()) {
            throw new ConflictException("Cannot place an order from an empty cart");
        }
        // ADR-0012 (the cart-integration slice): the re-pricing walk is
        // variant-aware — a variant line re-resolves through the VARIANT's
        // own authoritative record (both shelf states gate it), a
        // product-level line keeps the product's own record exactly as
        // before. The reserved maps split the same way, so the whole-cart
        // all-or-nothing reservation spans BOTH the product shelves and
        // the variant shelves (a refusal of either rolls the WHOLE
        // placement back — no order row in ANY group, no partial hold).
        java.util.Map<UUID, List<CartItem>> groups = new java.util.LinkedHashMap<>();
        java.util.List<ProductStockPort.StockLine> productStockLines = new java.util.ArrayList<>();
        java.util.List<ProductStockPort.StockLine> variantStockLines = new java.util.ArrayList<>();
        for (CartItem line : lines) {
            final UUID sellerId;
            final String authoritativeCurrency;
            final long authoritativePriceMinor;
            if (line.getVariantId() != null) {
                ProductPricingPort.VariantPrice variant =
                        productPricingPort.priceOfVariant(line.getVariantId());
                if (!variant.purchasable()) {
                    throw new ConflictException("Variant " + line.getVariantId() + " is not "
                            + "purchasable — product " + line.getProductId() + " is "
                            + variant.productStorefront() + ", variant is "
                            + variant.variantStorefront() + " — the cart line is no longer "
                            + "purchasable");
                }
                sellerId = variant.providerId();
                authoritativeCurrency = variant.currency();
                authoritativePriceMinor = variant.priceMinor();
                variantStockLines.add(new ProductStockPort.StockLine(
                        line.getVariantId(), line.getQuantity()));
            } else {
                ProductPricingPort.ProductPrice price = productPricingPort.priceOf(line.getProductId());
                if (price.storefront() != ProductPricingPort.StorefrontState.ACTIVE) {
                    throw new ConflictException("Product " + line.getProductId() + " is "
                            + price.storefront() + " — the cart line is no longer purchasable");
                }
                sellerId = price.providerId();
                authoritativeCurrency = price.currency();
                authoritativePriceMinor = price.priceMinor();
                productStockLines.add(new ProductStockPort.StockLine(
                        line.getProductId(), line.getQuantity()));
            }
            if (!line.getCurrency().equals(authoritativeCurrency)) {
                throw new ConflictException("Cart line " + line.getId() + " carries "
                        + line.getCurrency() + " but the purchasable unit now prices in "
                        + authoritativeCurrency + " — re-add the line before placing");
            }
            if (authoritativePriceMinor != line.getUnitAmountMinor()) {
                line.updateUnitAmountMinor(authoritativePriceMinor);
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
        for (java.util.Map.Entry<UUID, List<CartItem>> group : groups.entrySet()) {
            UUID sellerId = group.getKey();
            List<CartItem> groupLines = group.getValue();
            long total = 0;
            for (CartItem line : groupLines) {
                total += line.lineTotalMinor();
            }
            Order order = orderRepository.save(
                    Order.placed(consumerId, total, groupLines.get(0).getCurrency(), sellerId));
            List<OrderItem> frozen = new java.util.ArrayList<>(groupLines.size());
            for (CartItem line : groupLines) {
                frozen.add(orderItemRepository.save(OrderItem.snapshotOf(order.getId(), line)));
            }
            order.markStockReserved();
            placed.add(new OrderDetail(order, List.copyOf(frozen)));
        }
        cart.checkOut(Instant.now());
        cartRepository.save(cart);
        // PLACED publishes nothing — the A-03 discipline (no event without a
        // listener); the machine's cross-boundary information begins at
        // CONFIRMED (and the cancellation edge's OrderCancelledEvent).
        return List.copyOf(placed);
    }

    /**
     * PLACED → CONFIRMED. The merchant-side acceptance surface (the store's
     * merchant console arrives with C.7/M1 — the machine and its guard are
     * the load-bearing machinery today, exercised by the journey gate).
     */
    @Transactional
    @Observed(name = "order.confirm")
    public Order confirm(UUID orderId) {
        Order order = requireOrder(orderId);
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
     */
    @Override
    @Transactional
    @Observed(name = "order.fulfill.delivery")
    public void fulfillFromDelivery(UUID orderId, UUID deliveryRequestId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() == OrderStatus.FULFILLED
                && deliveryRequestId.equals(order.getDeliveryRequestId())) {
            return;
        }
        if (order.getStatus() != OrderStatus.CONFIRMED
                || !deliveryRequestId.equals(order.getDeliveryRequestId())) {
            throw new ConflictException("Delivery completion does not match the confirmed linked order");
        }
        Instant at = Instant.now();
        order.fulfill(at);
        if (order.isStockReserved()) {
            productStockPort.commit(stockMapOf(order.getId()));
            order.markStockReleased();
        }
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFulfilledEvent(order.getId(), order.getConsumerId()));
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

    @Transactional
    @Observed(name = "order.fulfill")
    public Order fulfill(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() == OrderStatus.CONFIRMED && order.getDeliveryRequestId() != null) {
            throw new ConflictException("An order linked to an active delivery must be fulfilled through its delivery lifecycle");
        }
        requireStatus(order, OrderStatus.CONFIRMED, "fulfill");
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
        if (order.getDeliveryRequestId() != null) {
            throw new ConflictException("An order linked to an active delivery must be cancelled through its delivery lifecycle");
        }
        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("Order " + orderId + " is " + order.getStatus()
                    + " — cancel is legal from PLACED or CONFIRMED only");
        }
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
        orderRepository.findByPaymentIntentId(paymentIntentId)
                .filter(order -> order.getStatus() == OrderStatus.PLACED)
                .ifPresentOrElse(order -> confirm(order.getId()),
                        () -> {
                            // The honest no-op: the order moved past PLACED
                            // (or carries no such intent) — the machine's
                            // current state is already the newer truth.
                        });
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
     * {@link #fulfill}; the guard is the seller scope and the read-back is
     * the command's own body.
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
