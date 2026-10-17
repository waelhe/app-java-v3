package com.marketplace.orders;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderRefundPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The V225 RMA machine (the Saleor fit-gap study §7-Phase-3 item 4: «Model
 * returns/exchanges separately from payment refunds and disputes»): the
 * return's OWN guarded lifecycle — REQUESTED → APPROVED → RECEIVED →
 * COMPLETED — where each edge belongs to its owner:
 *
 * <ul>
 *   <li>REQUEST: the buyer (the FULFILLED order's own buyer — the privacy
 *       contract), against lines that belong to the order, in quantities
 *       the frozen lines cover, with no overlapping open return for the
 *       same item.</li>
 *   <li>APPROVE: the operator — computes and FREEZES the refund share from
 *       the order's own frozen breakdown (the lines' share of the
 *       discounted goods value, plus the exclusive-tax share when the
 *       jurisdiction adds tax).</li>
 *   <li>RECEIVE: the operator — the goods arrive; the shelves restock
 *       through {@link ProductStockPort#restock} (the physical truth).</li>
 *   <li>COMPLETE: the operator — a RETURN executes the frozen partial
 *       refund through the ONE refund contract
 *       ({@link OrderRefundPort}); an EXCHANGE creates the REPLACEMENT
 *       order (same seller, same lines, zero payment — the buyer already
 *       paid) and links it.</li>
 * </ul>
 */
@Service
public class OrderReturnsService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderReturnRepository returns;
    private final OrderReturnLineRepository returnLines;
    private final ProductStockPort productStockPort;
    private final OrderRefundPort orderRefundPort;
    private final ApplicationEventPublisher eventPublisher;

    public OrderReturnsService(OrderRepository orderRepository,
                               OrderItemRepository orderItemRepository,
                               OrderReturnRepository returns,
                               OrderReturnLineRepository returnLines,
                               ProductStockPort productStockPort,
                               OrderRefundPort orderRefundPort,
                               ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.returns = returns;
        this.returnLines = returnLines;
        this.productStockPort = productStockPort;
        this.orderRefundPort = orderRefundPort;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------
    // REQUEST (the buyer's edge)
    // ------------------------------------------------------------------

    /**
     * Opens a return/exchange request against a FULFILLED order — the
     * buyer's own order (the honest 404 otherwise), lines that belong to
     * it, quantities the frozen lines cover, and no overlapping return for
     * the same item (the PR objective's limit: COMPLETED and OPEN returns
     * both count — a completed return's units are already refunded, so its
     * quantity stays consumed; REQUESTED/APPROVED/RECEIVED likewise block
     * a second request for their items). The order row is locked
     * PESSIMISTIC_WRITE for the whole guard+insert: two concurrent requests
     * serialize on the row instead of both reading the same open-return
     * total before either inserts its lines (the review's finding).
     */
    @Transactional
    @Observed(name = "order.return.request")
    public OrderReturn request(UUID orderId, UUID buyerId, OrderReturn.ReturnType type,
                               String reason, Map<UUID, Integer> lineRequests) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getConsumerId().equals(buyerId))
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.FULFILLED) {
            throw new ConflictException("Order " + orderId + " is " + order.getStatus()
                    + " — a return is legal against a FULFILLED order only");
        }
        if (lineRequests == null || lineRequests.isEmpty()) {
            throw new BadRequestException("A return requires at least one line");
        }
        List<OrderItem> frozenLines = orderItemRepository.findByOrderId(orderId);
        List<OrderReturn> open = returns.findByOrderIdAndStatusIn(orderId, List.of(
                OrderReturn.ReturnStatus.REQUESTED, OrderReturn.ReturnStatus.APPROVED,
                OrderReturn.ReturnStatus.RECEIVED, OrderReturn.ReturnStatus.COMPLETED));
        Map<UUID, Integer> alreadyRequested = new HashMap<>();
        for (OrderReturn openReturn : open) {
            for (OrderReturnLine line : returnLines.findByOrderReturnId(openReturn.getId())) {
                alreadyRequested.merge(line.getOrderItemId(), line.getQuantity(), Integer::sum);
            }
        }
        OrderReturn requested = returns.save(OrderReturn.requested(
                orderId, buyerId, type, reason, order.getCurrency(), Instant.now()));
        for (Map.Entry<UUID, Integer> entry : lineRequests.entrySet()) {
            OrderItem item = frozenLines.stream()
                    .filter(l -> l.getId().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(
                            "Order item " + entry.getKey() + " is not a line of order " + orderId));
            int already = alreadyRequested.getOrDefault(item.getId(), 0);
            if (entry.getValue() + already > item.getQuantity()) {
                throw new ConflictException("Order item " + item.getId() + " covers "
                        + item.getQuantity() + " units — " + already + " already in open returns, "
                        + entry.getValue() + " more requested");
            }
            returnLines.save(OrderReturnLine.of(requested.getId(), item, entry.getValue()));
        }
        return requested;
    }

    // ------------------------------------------------------------------
    // The operator's edges (APPROVE / REJECT / RECEIVE / COMPLETE)
    // ------------------------------------------------------------------

    /** Freezes the refund share — the order's own frozen breakdown is the only input. */
    @Transactional
    @Observed(name = "order.return.approve")
    public OrderReturn approve(UUID returnId) {
        OrderReturn r = require(returnId);
        long share = frozenRefundShareOf(r);
        r.approve(share, Instant.now());
        return returns.save(r);
    }

    @Transactional
    @Observed(name = "order.return.reject")
    public OrderReturn reject(UUID returnId, String note) {
        OrderReturn r = require(returnId);
        r.reject(note, Instant.now());
        return returns.save(r);
    }

    /** The goods arrive — the shelves restock (the physical truth). */
    @Transactional
    @Observed(name = "order.return.receive")
    public OrderReturn receive(UUID returnId) {
        OrderReturn r = require(returnId);
        r.receive(Instant.now());
        OrderReturn saved = returns.save(r);
        restockLines(saved);
        return saved;
    }

    /**
     * The terminal edge: a RETURN executes the FROZEN partial refund
     * through the ONE refund contract; an EXCHANGE creates the replacement
     * order (same seller, same lines, zero payment — the buyer already
     * paid) and links it. Idempotent by the state guard — a retried
     * completion of an already-COMPLETED return is a no-op read. The return
     * row loads under PESSIMISTIC_WRITE (the review's Major finding): two
     * concurrent completes serialize on the row — the second finds
     * COMPLETED and no-ops instead of both observing the pre-terminal
     * state and both reaching the refund port. The refund rides the
     * return-scoped PSP idempotency key (stable across a rollback+retry —
     * see {@code OrderRefundPort}).
     */
    @Transactional
    @Observed(name = "order.return.complete")
    public OrderReturn complete(UUID returnId) {
        OrderReturn r = returns.findByIdForUpdate(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
        if (r.getStatus() == OrderReturn.ReturnStatus.COMPLETED) {
            return r;
        }
        if (r.getReturnType() == OrderReturn.ReturnType.RETURN) {
            orderRefundPort.refundForOrder(r.getOrderId(), r.getId(), r.getRefundAmountMinor());
            r.complete(null, Instant.now());
            return returns.save(r);
        }
        // the EXCHANGE's replacement: same seller, same lines, ZERO payment
        Order order = orderRepository.findById(r.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", r.getOrderId()));
        List<OrderItem> originals = orderItemRepository.findByOrderId(r.getOrderId());
        Map<UUID, Integer> productQuantities = new HashMap<>();
        Map<UUID, Integer> variantQuantities = new HashMap<>();
        Order replacement = orderRepository.save(Order.placedWithBreakdown(
                order.getConsumerId(), order.getCurrency(), order.getSellerId(),
                0, 0, 0, true));
        for (OrderReturnLine line : returnLines.findByOrderReturnId(returnId)) {
            OrderItem original = originals.stream()
                    .filter(i -> i.getId().equals(line.getOrderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("OrderItem", line.getOrderItemId()));
            orderItemRepository.save(OrderItem.snapshotOfReturn(replacement.getId(), original,
                    line.getQuantity()));
            // the restock path's own split (the review's Major finding): a
            // variant line reserves on the VARIANT's own shelf — the
            // product-level counter alone would leave the shelf
            // under-reserved and the later commit would fail with the
            // honest conflict instead of shipping the replacement
            if (line.getVariantId() != null) {
                variantQuantities.merge(line.getVariantId(), line.getQuantity(), Integer::sum);
            } else {
                productQuantities.merge(original.getProductId(), line.getQuantity(), Integer::sum);
            }
        }
        // the replacement needs its own shelf reservation — the goods ship again
        if (!productQuantities.isEmpty()) {
            productStockPort.reserve(productQuantities);
        }
        if (!variantQuantities.isEmpty()) {
            productStockPort.reserveVariants(variantQuantities);
        }
        replacement.markStockReserved();
        orderRepository.save(replacement);
        r.complete(replacement.getId(), Instant.now());
        return returns.save(r);
    }

    @Transactional
    @Observed(name = "order.return.cancel")
    public OrderReturn cancelForBuyer(UUID returnId, UUID buyerId) {
        OrderReturn r = require(returnId);
        if (!r.getConsumerId().equals(buyerId)) {
            throw new ResourceNotFoundException("Return", returnId);
        }
        r.cancel("cancelled by the buyer", Instant.now());
        return returns.save(r);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<OrderReturn> listMine(UUID buyerId, Pageable pageable) {
        return returns.findByConsumerIdOrderByCreatedAtDesc(buyerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<OrderReturn> listAdmin(Pageable pageable) {
        return returns.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public OrderReturn getForBuyer(UUID returnId, UUID buyerId) {
        OrderReturn r = require(returnId);
        if (!r.getConsumerId().equals(buyerId)) {
            throw new ResourceNotFoundException("Return", returnId);
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<OrderReturnLine> linesOf(UUID returnId) {
        return returnLines.findByOrderReturnId(returnId);
    }

    // ------------------------------------------------------------------
    // The frozen refund share (the order's own breakdown is the input)
    // ------------------------------------------------------------------

    /**
     * The lines' share of the DISCOUNTED goods value:
     * {@code Σ(unit × qty) × (subtotal − discount) / subtotal} — plus the
     * exclusive-tax share when the jurisdiction adds tax on top (an
     * INCLUSIVE tax is already inside the goods value). Shipping never
     * refunds on a return (a service rendered); the full-order refund
     * stays the cancellation's own path. Legacy orders (no breakdown
     * recorded) refund the plain goods value.
     */
    private long frozenRefundShareOf(OrderReturn r) {
        List<OrderItem> frozenLines = orderItemRepository.findByOrderId(r.getOrderId());
        long goodsValue = 0;
        for (OrderReturnLine line : returnLines.findByOrderReturnId(r.getId())) {
            OrderItem item = frozenLines.stream()
                    .filter(i -> i.getId().equals(line.getOrderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("OrderItem", line.getOrderItemId()));
            goodsValue += line.goodsValueMinor(item);
        }
        Order order = orderRepository.findById(r.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", r.getOrderId()));
        Long subtotal = order.getSubtotalAmountMinor();
        Long discount = order.getDiscountAmountMinor();
        if (subtotal == null || subtotal <= 0 || discount == null) {
            return goodsValue; // the legacy agreement: the plain goods value
        }
        long discountedGoods = subtotal - discount;
        long share = Math.round(goodsValue * (double) discountedGoods / subtotal);
        // the tax-mode witness: the frozen total MINUS any shipping the
        // placement froze equals the discounted goods PLUS tax only in an
        // EXCLUSIVE jurisdiction — an INCLUSIVE order's total already
        // contains its tax inside the discounted goods value. The prior
        // shape (subtotal + tax − discount > discountedGoods) reduced to
        // tax > 0 and misclassified every positive-tax INCLUSIVE order,
        // double-counting its contained tax on top of the share (the
        // review's Major finding).
        long totalExcludingShipping = order.getTotalAmountMinor() == null ? 0 : order.getTotalAmountMinor()
                - (order.getShippingAmountMinor() == null ? 0 : order.getShippingAmountMinor());
        boolean exclusiveJurisdiction = order.getTaxAmountMinor() != null && order.getTaxAmountMinor() > 0
                && totalExcludingShipping == discountedGoods + order.getTaxAmountMinor();
        // the exclusive-tax share rides the goods share proportionally
        if (exclusiveJurisdiction) {
            // EXCLUSIVE jurisdictions: the tax added on top of the goods
            long exclusiveTax = order.getTaxAmountMinor();
            long goodsRatio = Math.round(exclusiveTax * (double) goodsValue / subtotal);
            share += goodsRatio;
        }
        return Math.max(1, share);
    }

    private void restockLines(OrderReturn r) {
        List<OrderItem> frozenLines = orderItemRepository.findByOrderId(r.getOrderId());
        Map<UUID, Integer> productQuantities = new HashMap<>();
        Map<UUID, Integer> variantQuantities = new HashMap<>();
        for (OrderReturnLine line : returnLines.findByOrderReturnId(r.getId())) {
            if (line.getVariantId() != null) {
                variantQuantities.merge(line.getVariantId(), line.getQuantity(), Integer::sum);
            } else {
                productQuantities.merge(line.getProductId(), line.getQuantity(), Integer::sum);
            }
        }
        if (!productQuantities.isEmpty()) {
            productStockPort.restock(productQuantities);
        }
        if (!variantQuantities.isEmpty()) {
            productStockPort.restockVariants(variantQuantities);
        }
    }

    private OrderReturn require(UUID returnId) {
        return returns.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
    }
}
