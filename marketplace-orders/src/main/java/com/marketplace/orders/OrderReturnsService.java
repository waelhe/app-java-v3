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

    /**
     * The overlap guard's counted set (row 6): the return statuses whose
     * units stay consumed against the frozen lines — REQUESTED through
     * COMPLETED. Only REJECTED/CANCELLED give the units back.
     */
    private static final List<OrderReturn.ReturnStatus> QUANTITY_CONSUMING_STATUSES = List.of(
            OrderReturn.ReturnStatus.REQUESTED, OrderReturn.ReturnStatus.APPROVED,
            OrderReturn.ReturnStatus.RECEIVED, OrderReturn.ReturnStatus.COMPLETED);

    /**
     * Row 1's counted set — the statuses whose frozen share is already born
     * (REQUESTED carries none yet; REJECTED/CANCELLED never froze one): the
     * goods-and-tax budget sums exactly these.
     */
    private static final List<OrderReturn.ReturnStatus> SHARE_FROZEN_STATUSES = List.of(
            OrderReturn.ReturnStatus.APPROVED, OrderReturn.ReturnStatus.RECEIVED,
            OrderReturn.ReturnStatus.COMPLETED);

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
     *
     * <p><b>row 6 (agent findings 2026-10-25, CWE-400): the overlap history
     * reads through ONE fixed aggregate.</b> The prior shape loaded every
     * counted return of the order and walked its lines one query per return
     * — inside the locked transaction — before the quantity guard could
     * answer; a buyer could prepare many small returns and replay that
     * database work with every rejected request. The guard now reads
     * {@code sumConsumedByOrderItemId} — one statement, restricted to the
     * requested order-item IDs, grouped per item — and rejects excess
     * quantities without ever issuing a query per historical return.</p>
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
        // row 6: the counted set is the overlap guard's own vocabulary —
        // REQUESTED through COMPLETED keep their units consumed; only
        // REJECTED/CANCELLED give the units back.
        Map<UUID, Long> alreadyRequested = new HashMap<>();
        for (ConsumedReturnQuantity consumed : returnLines.sumConsumedByOrderItemId(
                orderId, lineRequests.keySet(), QUANTITY_CONSUMING_STATUSES)) {
            alreadyRequested.put(consumed.getOrderItemId(), consumed.getConsumedQuantity());
        }
        OrderReturn requested = returns.save(OrderReturn.requested(
                orderId, buyerId, type, reason, order.getCurrency(), Instant.now()));
        for (Map.Entry<UUID, Integer> entry : lineRequests.entrySet()) {
            OrderItem item = frozenLines.stream()
                    .filter(l -> l.getId().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(
                            "Order item " + entry.getKey() + " is not a line of order " + orderId));
            long already = alreadyRequested.getOrDefault(item.getId(), 0L);
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
        // CodeRabbit agent findings (2026-10-26T11:43 export), row 1: the
        // freeze's own money-safety gate — the cumulative frozen shares of
        // the order (this one included) stay inside the goods-and-tax
        // budget, so no combination of returns can ever reach the collected
        // SHIPPING charge (a service rendered — the full-order refund stays
        // the cancellation's own path).
        requireWithinGoodsRefundBudget(r.getOrderId(), share);
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
        // CodeRabbit round 2, verified real (the Major): the completion edge
        // guards its state BEFORE any side effect — the documented lifecycle
        // is RECEIVED -> COMPLETED. Anything else must answer the honest 409
        // UP FRONT: REQUESTED/REJECTED/CANCELLED carry a null frozen share
        // (the unboxing to long would 500), and APPROVED has the goods still
        // in transit — refunding there would move remote money that a local
        // rollback cannot undo.
        if (r.getStatus() != OrderReturn.ReturnStatus.RECEIVED) {
            throw new ConflictException("Return " + returnId + " is " + r.getStatus()
                    + " — completion requires RECEIVED (the goods back first)");
        }
        if (r.getReturnType() == OrderReturn.ReturnType.RETURN) {
            // CodeRabbit agent findings (2026-10-26T11:43 export), row 1:
            // a ZERO frozen share is the honest completion of a fully-
            // discounted goods basket — the return completes WITHOUT issuing
            // a payment refund (the pre-fix forced Math.max(1, share), which
            // minted one minor unit out of the collected, nonrefundable
            // shipping charge). The budget gate re-reads the cumulative
            // truth at the money edge — the same honest ceiling the freeze
            // answered at approval, guarding the window where sibling
            // returns froze after this one did.
            if (r.getRefundAmountMinor() > 0) {
                // prospectiveShare = 0: the completing return is RECEIVED — its
                // frozen share is ALREADY inside the counted aggregate; this
                // edge adds nothing, the gate just re-reads the cumulative
                // truth at the money edge (sibling freezes since approval).
                requireWithinGoodsRefundBudget(r.getOrderId(), 0);
                orderRefundPort.refundForOrder(r.getOrderId(), r.getId(), r.getRefundAmountMinor());
            }
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
        // CodeRabbit agent findings (2026-10-26T11:43 export), row 1: the
        // forced positive minimum is GONE. A zero goods share (fully
        // discounted basket, no applicable tax) freezes an honest ZERO —
        // the entity's own approve() contract (>= 0) and the zero-value
        // completion path carry it; one minor unit never again leaves the
        // collected shipping charge for goods that gave back nothing.
        return share;
    }

    /**
     * CodeRabbit agent findings (2026-10-26T11:43 export), row 1 — the
     * cumulative goods-and-tax refund budget that EXCLUDES shipping. The
     * order's own frozen breakdown carries the ceiling: {@code total −
     * shipping} is exactly the discounted goods plus tax in BOTH tax modes
     * (INCLUSIVE keeps its tax inside the goods; EXCLUSIVE added it on top),
     * so returns — which refund the goods share plus the exclusive-tax share,
     * never the shipping service — can never lawfully cross it. The guard
     * reads the one fixed aggregate ({@link OrderReturnRepository#
     * sumFrozenSharesByOrderIdAndStatusIn}: APPROVED/RECEIVED/COMPLETED —
     * the shares that will execute or already did) and refuses the honest
     * 409 BEFORE the freeze (approval) and BEFORE the money moves
     * (completion). The rounding allowance admits exactly the drift the
     * freeze protocol itself can produce: in an EXCLUSIVE-tax order each
     * return's frozen share rounds TWICE on its own (Math.round for the
     * goods share AND Math.round for the exclusive-tax share — up to one
     * full minor unit per share, the CodeRabbit review round on #741's
     * part 4, 2026-10-28), so k frozen shares drift less than k above
     * the exact total — a fully returned order still completes; only a
     * real overshoot answers 409.
     *
     * @param prospectiveShare the APPROVAL edge's own share — the return is
     *                         still REQUESTED there, OUTSIDE the counted
     *                         aggregate, so it joins the sum and the drift
     *                         count; ZERO at the completion edge, where the
     *                         completing return is already RECEIVED and its
     *                         frozen share already sits inside the aggregate
     * @throws ConflictException when the cumulative shares would cross the
     *                         order's goods-and-tax budget
     */
    private void requireWithinGoodsRefundBudget(UUID orderId, long prospectiveShare) {
        // CodeRabbit review round on #741's part 4 (2026-10-28, Minor —
        // Stability & Availability): the APPROVAL edge reads the order
        // under the row's write lock — the same findByIdForUpdate the
        // request edge already takes. Two operators approving different
        // returns for the same order under READ_COMMITTED both read the
        // frozen sum before either commits, both pass, and the pair then
        // strands itself in RECEIVED with no exit (the completion edge
        // re-checks the budget and 409s forever). The lock serializes the
        // approvals against the same order row; the ordering stays safe
        // (request: order only; complete: the return, then the order).
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        Long total = order.getTotalAmountMinor();
        Long shipping = order.getShippingAmountMinor();
        if (total == null || shipping == null) {
            return; // the legacy agreement — no frozen breakdown, no ceiling
        }
        long goodsAndTaxCeiling = total - shipping;
        int frozenCount = (int) returns.countByOrderIdAndStatusIn(orderId, SHARE_FROZEN_STATUSES);
        long cumulative = returns.sumFrozenSharesByOrderIdAndStatusIn(orderId, SHARE_FROZEN_STATUSES)
                + prospectiveShare;
        int shares = frozenCount + (prospectiveShare > 0 ? 1 : 0);
        // CodeRabbit review round on #741's part 4 (2026-10-28, Minor —
        // Functional Correctness): an EXCLUSIVE-tax order's frozen share
        // rounds TWICE per return (goods + tax), so the drift bound is one
        // unit per share, not half — two returns covering the full order
        // with .5 fractions each deviate +2 while shares/2 admits only 1,
        // and the operator got a spurious 409 on a fully legitimate final
        // approval. The INCLUSIVE shape never drifts more than the old
        // half-unit bound; the full-unit allowance stays the honest upper
        // bound for both (an overshoot beyond it is still a real 409).
        long roundingAllowance = shares;
        if (cumulative > goodsAndTaxCeiling + roundingAllowance) {
            throw new ConflictException("Return refunds would cross the order's goods-and-tax budget "
                    + goodsAndTaxCeiling + " (shipping never refunds on a return) — frozen "
                    + (cumulative - prospectiveShare) + " already, this share " + prospectiveShare);
        }
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
