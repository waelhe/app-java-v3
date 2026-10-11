package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import com.marketplace.shared.api.OrderPaymentPort;
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
public class OrdersService {

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
     * Adds a line (or raises the quantity when the product is already in
     * the cart — the unique {@code (cart_id, product_id)} key collapses the
     * duplicate add into the quantity bump).
     *
     * <p><b>The amount-source boundary (ADR-0002 — the closure of the
     * documented TODO):</b> the line's amount is the store's authoritative
     * product pricing, resolved through {@link ProductPricingPort} INSIDE
     * this transaction — no caller-supplied amount exists on the write
     * path. A non-ACTIVE product (suspended/archived/deleted) answers the
     * house 409: the storefront read already hides it, and the write path
     * re-checks the state at its own boundary.
     */
    @Transactional
    public CartItem addCartItem(UUID consumerId, UUID productId, int quantity) {
        ProductPricingPort.ProductPrice price = productPricingPort.priceOf(productId);
        if (price.storefront() != ProductPricingPort.StorefrontState.ACTIVE) {
            throw new ConflictException("Product " + productId + " is " + price.storefront()
                    + " — only ACTIVE products are purchasable");
        }
        Cart cart = getOrCreateActiveCart(consumerId);
        return cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .map(existing -> {
                    existing.updateQuantity(existing.getQuantity() + quantity);
                    return existing;
                })
                .orElseGet(() -> cartItemRepository.save(
                        CartItem.of(cart.getId(), productId, quantity,
                                price.priceMinor(), price.currency())));
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
        java.util.Map<UUID, List<CartItem>> groups = new java.util.LinkedHashMap<>();
        for (CartItem line : lines) {
            ProductPricingPort.ProductPrice price = productPricingPort.priceOf(line.getProductId());
            if (price.storefront() != ProductPricingPort.StorefrontState.ACTIVE) {
                throw new ConflictException("Product " + line.getProductId() + " is "
                        + price.storefront() + " — the cart line is no longer purchasable");
            }
            if (!line.getCurrency().equals(price.currency())) {
                throw new ConflictException("Cart line " + line.getId() + " carries "
                        + line.getCurrency() + " but the product now prices in " + price.currency()
                        + " — re-add the line before placing");
            }
            if (price.priceMinor() != line.getUnitAmountMinor()) {
                line.updateUnitAmountMinor(price.priceMinor());
            }
            groups.computeIfAbsent(price.providerId(), k -> new java.util.ArrayList<>()).add(line);
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
        // group, no partial hold).
        productStockPort.reserve(ProductStockPort.StockLine.asMap(lines.stream()
                .map(line -> new ProductStockPort.StockLine(line.getProductId(), line.getQuantity()))
                .toList()));
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
    @Transactional
    @Observed(name = "order.fulfill")
    public Order fulfill(UUID orderId) {
        Order order = requireOrder(orderId);
        requireStatus(order, OrderStatus.CONFIRMED, "fulfill");
        order.fulfill(Instant.now());
        if (order.isStockReserved()) {
            productStockPort.commit(stockMapOf(order.getId()));
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
        Order order = requireOrder(orderId);
        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("Order " + orderId + " is " + order.getStatus()
                    + " — cancel is legal from PLACED or CONFIRMED only");
        }
        order.cancel(reason, Instant.now());
        if (order.isStockReserved()) {
            productStockPort.release(stockMapOf(order.getId()));
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
     * V172 partial-unique index as the second line of defense) and links
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

    /** The lines' product→quantity map, one hop, for the stock seams. */
    private java.util.Map<UUID, Integer> stockMapOf(UUID orderId) {
        return ProductStockPort.StockLine.asMap(orderItemRepository.findByOrderId(orderId).stream()
                .map(item -> new ProductStockPort.StockLine(item.getProductId(), item.getQuantity()))
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
