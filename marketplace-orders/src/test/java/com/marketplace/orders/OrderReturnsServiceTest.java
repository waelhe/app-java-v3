package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderRefundPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The V225 RMA lifecycle tests (the Saleor fit-gap study §7-Phase-3
 * item 4): the return is its OWN machine — separate from payment refunds
 * and disputes — with the frozen refund share computed from the order's
 * own frozen breakdown, the restock on receipt, the ONE-refund-contract
 * completion, and the exchange's replacement order.
 */
@ExtendWith(MockitoExtension.class)
class OrderReturnsServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderReturnRepository returns;
    @Mock
    private OrderReturnLineRepository returnLines;
    @Mock
    private ProductStockPort productStockPort;
    @Mock
    private OrderRefundPort orderRefundPort;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderReturnsService service;

    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OrderReturnsService(orderRepository, orderItemRepository,
                returns, returnLines, productStockPort, orderRefundPort, eventPublisher);
        org.mockito.Mockito.lenient().when(returns.save(any(OrderReturn.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(returnLines.save(any(OrderReturnLine.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // row 1's budget aggregate: no frozen siblings and an empty sum by
        // default — every approval/Completion test starts from a clean budget
        org.mockito.Mockito.lenient()
                .when(returns.countByOrderIdAndStatusIn(any(), any())).thenReturn(0L);
        org.mockito.Mockito.lenient()
                .when(returns.sumFrozenSharesByOrderIdAndStatusIn(any(), any())).thenReturn(0L);
    }

    private Order fulfilledOrder() {
        Order order = Order.placedWithBreakdown(buyer, "SAR", seller,
                10_000L, 1_000L, 0L, true);
        order.confirm(Instant.now());
        order.fulfill(Instant.now());
        return order;
    }

    private OrderItem itemOf(Order order, UUID productId, int quantity, long unitMinor) {
        return OrderItem.snapshotOfReturn(order.getId(),
                OrderItem.snapshotOf(order.getId(),
                        com.marketplace.orders.CartItem.of(UUID.randomUUID(), productId, null,
                                quantity, unitMinor, "SAR")),
                quantity);
    }

    /** row 6: one aggregate row of the overlap guard's consumed quantities. */
    private ConsumedReturnQuantity consumed(UUID itemId, long quantity) {
        return new ConsumedReturnQuantity() {
            @Override
            public UUID getOrderItemId() {
                return itemId;
            }

            @Override
            public long getConsumedQuantity() {
                return quantity;
            }
        };
    }

    @Test
    void aBuyerOpensAReturnAgainstTheirFulfilledOrder() {
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(returnLines.sumConsumedByOrderItemId(eq(order.getId()), any(), any()))
                .thenReturn(List.of());

        OrderReturn opened = service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", Map.of(item.getId(), 1));

        assertThat(opened.getStatus()).isEqualTo(OrderReturn.ReturnStatus.REQUESTED);
        assertThat(opened.getReturnType()).isEqualTo(OrderReturn.ReturnType.RETURN);
        verify(returnLines).save(any(OrderReturnLine.class));
    }

    @Test
    void aNonFulfilledOrderRefusesTheReturn() {
        Order order = Order.placed(buyer, 5_000L, "SAR", seller);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "changed mind", Map.of(UUID.randomUUID(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FULFILLED order only");
    }

    @Test
    void anotherBuyersOrderAnswersTheHonest404() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(orderId, buyer,
                OrderReturn.ReturnType.RETURN, "no", Map.of(UUID.randomUUID(), 1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void quantitiesBeyondTheFrozenLinePlusOpenReturnsRefuse() {
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(returnLines.sumConsumedByOrderItemId(eq(order.getId()), any(), any()))
                .thenReturn(List.of(consumed(item.getId(), 1L)));

        assertThatThrownBy(() -> service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "second", Map.of(item.getId(), 2)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already in open returns");
    }

    @Test
    void theOverlapGuardReadsOneFixedAggregate_noPerReturnWalk() {
        // row 6 (agent findings 2026-10-25, CWE-400): however many counted
        // returns the order carries, the guard reads its consumed quantities
        // through ONE aggregate statement — restricted to the requested
        // order-item IDs and the counted statuses — and the per-return
        // findByOrderReturnId walk (one query per historical return, inside
        // the locked transaction) never runs
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 5, 3_000L);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(returnLines.sumConsumedByOrderItemId(eq(order.getId()), any(), any()))
                .thenReturn(List.of(consumed(item.getId(), 4L)));

        service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "one more", Map.of(item.getId(), 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> itemIds =
                ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<OrderReturn.ReturnStatus>> statuses =
                ArgumentCaptor.forClass(Collection.class);
        verify(returnLines).sumConsumedByOrderItemId(eq(order.getId()),
                itemIds.capture(), statuses.capture());
        assertThat(itemIds.getValue()).containsExactly(item.getId());
        assertThat(statuses.getValue()).containsExactly(
                OrderReturn.ReturnStatus.REQUESTED, OrderReturn.ReturnStatus.APPROVED,
                OrderReturn.ReturnStatus.RECEIVED, OrderReturn.ReturnStatus.COMPLETED);
        verify(returnLines, never()).findByOrderReturnId(any());
        verify(returnLines).save(any(OrderReturnLine.class));
    }

    @Test
    void approvalFreezesTheDiscountedGoodsShare() {
        Order order = fulfilledOrder(); // subtotal 10000, discount 1000, tax 0 (INCLUSIVE)
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 1)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        OrderReturn approved = service.approve(r.getId());

        // goods 3000 × (10000−1000)/10000 = 2700 — the discounted share, frozen
        assertThat(approved.getStatus()).isEqualTo(OrderReturn.ReturnStatus.APPROVED);
        assertThat(approved.getRefundAmountMinor()).isEqualTo(2_700L);
    }

    @Test
    void completedReturnsCountTowardTheQuantityGuard() {
        // the PR objective's limit (the review's Major finding): a COMPLETED
        // return's units are already refunded — its quantity stays consumed,
        // so a second return for the same units cannot pass the guard and
        // double-refund the same goods. The counted set (captured in the
        // fixed-aggregate test below) carries COMPLETED alongside the open
        // statuses — the aggregate's verdict is the same refusal
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(returnLines.sumConsumedByOrderItemId(eq(order.getId()), any(), any()))
                .thenReturn(List.of(consumed(item.getId(), 2L)));

        assertThatThrownBy(() -> service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "second", Map.of(item.getId(), 2)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already in open returns");
    }

    @Test
    void anInclusiveOrderWithPositiveTaxTakesNoExtraTaxShare() {
        // the review's Major finding: the prior witness (tax > 0) added the
        // exclusive-tax ratio to INCLUSIVE orders whose tax is already
        // contained in the discounted goods — the frozen total is the only
        // honest mode witness
        Order order = Order.placedWithBreakdown(buyer, "SAR", seller,
                10_000L, 1_000L, 500L, true); // INCLUSIVE: total 9000 = goods, tax inside
        order.confirm(Instant.now());
        order.fulfill(Instant.now());
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 1)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        OrderReturn approved = service.approve(r.getId());

        // goods 3000 × (10000−1000)/10000 = 2700 — the contained tax adds
        // NOTHING on top (an INCLUSIVE order's goods value already carries it)
        assertThat(approved.getRefundAmountMinor()).isEqualTo(2_700L);
    }

    @Test
    void approvalAddsTheExclusiveTaxShareProportionally() {
        // subtotal 10000, discount 0, tax 1500 EXCLUSIVE → total 11500
        Order order = Order.placedWithBreakdown(buyer, "SAR", seller, 10_000L, 0L, 1_500L, false);
        order.confirm(Instant.now());
        order.fulfill(Instant.now());
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "broken", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 1)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThat(service.approve(r.getId()).getRefundAmountMinor())
                // goods 3000 + tax share 1500×3000/10000 = 450 → 3450
                .isEqualTo(3_450L);
    }

    @Test
    void receiptRestocksTheShelves() {
        Order order = fulfilledOrder();
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        OrderItem productItem = itemOf(order, product, 2, 3_000L);
        OrderItem variantItem = OrderItem.snapshotOfReturn(order.getId(),
                OrderItem.snapshotOf(order.getId(),
                        CartItem.of(UUID.randomUUID(), product, variant, 1, 2_000L, "SAR")), 1);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "both", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), productItem, 2),
                OrderReturnLine.of(r.getId(), variantItem, 1)));
        when(orderItemRepository.findByOrderId(order.getId()))
                .thenReturn(List.of(productItem, variantItem));

        OrderReturn received = service.receive(r.getId());

        assertThat(received.getStatus()).isEqualTo(OrderReturn.ReturnStatus.RECEIVED);
        verify(productStockPort).restock(Map.of(product, 2));
        verify(productStockPort).restockVariants(Map.of(variant, 1));
    }

    @Test
    void aReturnCompletionExecutesTheFrozenShareThroughTheOneRefundContract() {
        Order order = fulfilledOrder();
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        r.receive(Instant.now());
        when(returns.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        // row 1: the money edge re-reads the budget's ceiling from the order
        // (this legacy-agreement order carries no shipping breakdown — the
        // gate passes through untouched, the frozen share still executes)
when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRefundPort.refundForOrder(order.getId(), r.getId(), 2_700L)).thenReturn(
                new RefundOutcome(UUID.randomUUID(), 2_700L));

        OrderReturn completed = service.complete(r.getId());

        assertThat(completed.getStatus()).isEqualTo(OrderReturn.ReturnStatus.COMPLETED);
        verify(orderRefundPort).refundForOrder(order.getId(), r.getId(), 2_700L);
    }

    // ------------------------------------------------------------------
    // CodeRabbit agent findings (2026-10-26T11:43 export), row 1 — the
    // zero-value return path and the goods-and-tax refund budget
    // ------------------------------------------------------------------

    /** The order with a REAL shipping charge recorded (V224's breakdown law). */
    private Order fulfilledOrderWithShipping(long subtotalMinor, long discountMinor,
                                             long taxMinor, boolean taxInclusive, long shippingMinor) {
        Order order = Order.placedWithFulfillment(buyer, "SAR", seller,
                subtotalMinor, discountMinor, taxMinor, taxInclusive,
                FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh", 24.7136, 46.6753, shippingMinor);
        order.confirm(Instant.now());
        order.fulfill(Instant.now());
        return order;
    }

    @Test
    void aFullyDiscountedBasketFreezesAnHonestZeroShare() {
        // subtotal 10000 fully discounted, no tax, shipping 500 collected:
        // the goods gave back NOTHING — the pre-fix forced Math.max(1, share)
        // and minted one minor unit out of the nonrefundable shipping charge
        Order order = fulfilledOrderWithShipping(10_000L, 10_000L, 0L, true, 500L);
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "arrived late", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 2)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        OrderReturn approved = service.approve(r.getId());

        // goods 6000 × (10000−10000)/10000 = 0 — the honest ZERO freeze
        assertThat(approved.getStatus()).isEqualTo(OrderReturn.ReturnStatus.APPROVED);
        assertThat(approved.getRefundAmountMinor()).isZero();
    }

    @Test
    void aZeroValueCompletionRefundsNothingAndStillCompletes() {
        // the recommendation's first half: "Allow zero-value returns to
        // complete without issuing a payment refund" — the port is never
        // touched; the lifecycle still reaches its terminal state
        Order order = fulfilledOrderWithShipping(10_000L, 10_000L, 0L, true, 500L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "arrived late", "SAR", Instant.now());
        r.approve(0L, Instant.now());
        r.receive(Instant.now());
        when(returns.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));

        OrderReturn completed = service.complete(r.getId());

        assertThat(completed.getStatus()).isEqualTo(OrderReturn.ReturnStatus.COMPLETED);
        verify(orderRefundPort, never()).refundForOrder(any(), any(), anyLong());
    }

    @Test
    void theGoodsAndTaxBudgetRefusesSharesThatWouldReachTheShippingCharge() {
        // subtotal 10000, discount 1000, no tax, shipping 500: the ceiling is
        // total − shipping = 9000. A sibling already froze 9000; this return's
        // 5400 would pay the buyer out of the SHIPPING money — the honest 409
        Order order = fulfilledOrderWithShipping(10_000L, 1_000L, 0L, true, 500L);
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "second", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 2)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(returns.countByOrderIdAndStatusIn(eq(order.getId()), any())).thenReturn(1L);
        when(returns.sumFrozenSharesByOrderIdAndStatusIn(eq(order.getId()), any()))
                .thenReturn(9_000L);

        assertThatThrownBy(() -> service.approve(r.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("goods-and-tax budget");
    }

    @Test
    void theRoundingDriftOfALegitimateFullReturnStaysInsideTheBudget() {
        // the allowance half of the law: each return rounds its own share
        // (Math.round, at most half a unit up) — a FULLY returned order may
        // sum one unit above the exact ceiling and must still complete
        Order order = fulfilledOrderWithShipping(10_000L, 1_000L, 0L, true, 500L);
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "second", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 2)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(returns.countByOrderIdAndStatusIn(eq(order.getId()), any())).thenReturn(1L);
        // the sibling froze the exact ceiling; this return's own goods share
        // rounds to 1 above nothing (goods 6000 × 0.9 = 5400 is refused in the
        // crossing test — here the sibling froze 3600, this 5400 totals the
        // exact 9000 ceiling: passes with zero margin, the honest full return
        when(returns.sumFrozenSharesByOrderIdAndStatusIn(eq(order.getId()), any()))
                .thenReturn(3_600L);

        OrderReturn approved = service.approve(r.getId());

        assertThat(approved.getRefundAmountMinor()).isEqualTo(5_400L);
        assertThat(approved.getStatus()).isEqualTo(OrderReturn.ReturnStatus.APPROVED);
    }

    @Test
    void anExchangeCompletionCreatesTheZeroPaymentReplacementAndReservesItsShelf() {
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.EXCHANGE, "different size", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        r.receive(Instant.now());
        when(returns.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 2)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderReturn completed = service.complete(r.getId());

        assertThat(completed.getStatus()).isEqualTo(OrderReturn.ReturnStatus.COMPLETED);
        assertThat(completed.getReplacementOrderId()).isNotNull();
        // the replacement carries the same goods at the returned quantity
        ArgumentCaptor<Order> replacement = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(replacement.capture());
        Order saved = replacement.getAllValues().stream()
                .filter(o -> o.getId().equals(completed.getReplacementOrderId()))
                .findFirst().orElseThrow();
        assertThat(saved.getSellerId()).isEqualTo(seller);
        assertThat(saved.getTotalAmountMinor()).isZero();
        assertThat(saved.isStockReserved()).isTrue();
        verify(productStockPort).reserve(Map.of(item.getProductId(), 2));
        // an exchange never moves money
        verify(orderRefundPort, never()).refundForOrder(any(), any(), anyLong());
    }

    @Test
    void aCompletedReturnIsIdempotentOnRetry() {
        Order order = fulfilledOrder();
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        r.receive(Instant.now());
        r.complete(null, Instant.now());
        when(returns.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));

        OrderReturn completed = service.complete(r.getId());

        assertThat(completed.getStatus()).isEqualTo(OrderReturn.ReturnStatus.COMPLETED);
        verify(orderRefundPort, never()).refundForOrder(any(), any(), anyLong());
    }

    @Test
    void completingARequestedReturnAnswersTheHonest409BeforeAnySideEffect() {
        // CodeRabbit round 2, verified real (the Major): REQUESTED carries a
        // null frozen share — the old shape unboxed it to long inside the
        // refund call and answered a 500; the pre-guard answers the honest
        // 409 and never reaches the port
        Order order = fulfilledOrder();
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        when(returns.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.complete(r.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requires RECEIVED");
        verify(orderRefundPort, never()).refundForOrder(any(), any(), anyLong());
    }

    @Test
    void completingAnApprovedReturnNeverMovesRemoteMoney() {
        // CodeRabbit round 2, verified real (the Major): APPROVED has the
        // goods still in transit — the old shape called the refund port
        // FIRST and the entity guard rolled back only the LOCAL books,
        // leaving an unrecoverable remote refund; the pre-guard keeps the
        // money untouched until the goods are actually received
        Order order = fulfilledOrder();
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        when(returns.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.complete(r.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requires RECEIVED");
        verify(orderRefundPort, never()).refundForOrder(any(), any(), anyLong());
    }

    @Test
    void theBuyerCancelsWhileRequestedOnly() {
        Order order = fulfilledOrder();
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "changed mind", "SAR", Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));

        assertThat(service.cancelForBuyer(r.getId(), buyer).getStatus())
                .isEqualTo(OrderReturn.ReturnStatus.CANCELLED);

        // a SECOND return that already moved past REQUESTED refuses the edge
        OrderReturn decided = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "second", "SAR", Instant.now());
        decided.approve(100L, Instant.now());
        when(returns.findById(decided.getId())).thenReturn(Optional.of(decided));
        assertThatThrownBy(() -> service.cancelForBuyer(decided.getId(), buyer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REQUESTED only");
    }
}
