package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
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
 */
@Service
public class OrdersService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public OrdersService(CartRepository cartRepository,
                         CartItemRepository cartItemRepository,
                         OrderRepository orderRepository,
                         OrderItemRepository orderItemRepository,
                         CurrentUserProvider currentUserProvider,
                         ApplicationEventPublisher eventPublisher) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
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
     * <p><b>The amount-source boundary (documented):</b> {@code unitAmountMinor}
     * arrives from the caller today; the store's authoritative product
     * pricing (compliance plan C.7/M1, unit A-17) replaces the SOURCE, never
     * the snapshot path — the fields below are the buyer-agreement record
     * the order will freeze.
     */
    @Transactional
    public CartItem addCartItem(UUID consumerId, UUID productId, int quantity,
                                long unitAmountMinor, String currency) {
        Cart cart = getOrCreateActiveCart(consumerId);
        return cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .map(existing -> {
                    existing.updateQuantity(existing.getQuantity() + quantity);
                    return existing;
                })
                .orElseGet(() -> cartItemRepository.save(
                        CartItem.of(cart.getId(), productId, quantity, unitAmountMinor, currency)));
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
     * Cart to order: the placement transaction — the order row, every
     * snapshot line, and the cart's CHECKED_OUT tombstone commit together,
     * or nothing does. The total is derived (never caller-supplied): the
     * sum of the frozen lines. Returns the view assembly (order + frozen
     * lines) so the HTTP boundary chains without an entity local.
     */
    @Transactional
    @Observed(name = "order.place")
    public OrderDetail place(UUID consumerId) {
        Cart cart = cartRepository.findByConsumerIdAndStatus(consumerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException(
                        "No active cart to place: the buyer's draft is empty"));
        List<CartItem> lines = cartItemRepository.findByCartId(cart.getId());
        if (lines.isEmpty()) {
            throw new ConflictException("Cannot place an order from an empty cart");
        }
        String currency = lines.get(0).getCurrency();
        long total = 0;
        for (CartItem line : lines) {
            if (!line.getCurrency().equals(currency)) {
                throw new ConflictException("Mixed-currency cart cannot be placed: "
                        + line.getCurrency() + " line against " + currency + " cart");
            }
            total += line.lineTotalMinor();
        }
        Order order = orderRepository.save(
                Order.placed(consumerId, total, currency));
        List<OrderItem> frozen = new java.util.ArrayList<>(lines.size());
        for (CartItem line : lines) {
            frozen.add(orderItemRepository.save(OrderItem.snapshotOf(order.getId(), line)));
        }
        cart.checkOut(Instant.now());
        cartRepository.save(cart);
        // PLACED publishes nothing — the A-03 discipline (no event without a
        // listener); the machine's cross-boundary information begins at
        // CONFIRMED.
        return new OrderDetail(order, List.copyOf(frozen));
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
     * CONFIRMED → FULFILLED (terminal). The delivery completion.
     */
    @Transactional
    @Observed(name = "order.fulfill")
    public Order fulfill(UUID orderId) {
        Order order = requireOrder(orderId);
        requireStatus(order, OrderStatus.CONFIRMED, "fulfill");
        order.fulfill(Instant.now());
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFulfilledEvent(order.getId(), order.getConsumerId()));
        return saved;
    }

    /**
     * PLACED/CONFIRMED → CANCELLED (terminal). The buyer's own escape hatch
     * (the controller route) and the machine's administrative one share the
     * same guarded edge.
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
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderCancelledEvent(
                order.getId(), order.getConsumerId(), reason));
        return saved;
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
