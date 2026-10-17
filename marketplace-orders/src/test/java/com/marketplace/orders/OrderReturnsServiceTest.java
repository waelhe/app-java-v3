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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Test
    void aBuyerOpensAReturnAgainstTheirFulfilledOrder() {
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(returns.findByOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(order.getId()), any())).thenReturn(List.of());

        OrderReturn opened = service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", Map.of(item.getId(), 1));

        assertThat(opened.getStatus()).isEqualTo(OrderReturn.ReturnStatus.REQUESTED);
        assertThat(opened.getReturnType()).isEqualTo(OrderReturn.ReturnType.RETURN);
        verify(returnLines).save(any(OrderReturnLine.class));
    }

    @Test
    void aNonFulfilledOrderRefusesTheReturn() {
        Order order = Order.placed(buyer, 5_000L, "SAR", seller);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "changed mind", Map.of(UUID.randomUUID(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FULFILLED order only");
    }

    @Test
    void anotherBuyersOrderAnswersTheHonest404() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(orderId, buyer,
                OrderReturn.ReturnType.RETURN, "no", Map.of(UUID.randomUUID(), 1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void quantitiesBeyondTheFrozenLinePlusOpenReturnsRefuse() {
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        OrderReturn open = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "first", "SAR", Instant.now());
        when(returns.findByOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(order.getId()), any())).thenReturn(List.of(open));
        when(returnLines.findByOrderReturnId(open.getId())).thenReturn(List.of(
                OrderReturnLine.of(open.getId(), item, 1)));

        assertThatThrownBy(() -> service.request(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "second", Map.of(item.getId(), 2)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already in open returns");
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

        OrderReturn approved = service.approve(r.getId());

        // goods 3000 × (10000−1000)/10000 = 2700 — the discounted share, frozen
        assertThat(approved.getStatus()).isEqualTo(OrderReturn.ReturnStatus.APPROVED);
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
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(orderRefundPort.refundForOrder(order.getId(), 2_700L)).thenReturn(
                new RefundOutcome(UUID.randomUUID(), 2_700L));

        OrderReturn completed = service.complete(r.getId());

        assertThat(completed.getStatus()).isEqualTo(OrderReturn.ReturnStatus.COMPLETED);
        verify(orderRefundPort).refundForOrder(order.getId(), 2_700L);
    }

    @Test
    void anExchangeCompletionCreatesTheZeroPaymentReplacementAndReservesItsShelf() {
        Order order = fulfilledOrder();
        OrderItem item = itemOf(order, UUID.randomUUID(), 2, 3_000L);
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.EXCHANGE, "different size", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        r.receive(Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));
        when(returnLines.findByOrderReturnId(r.getId())).thenReturn(List.of(
                OrderReturnLine.of(r.getId(), item, 2)));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
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
        verify(orderRefundPort, never()).refundForOrder(any(), anyLong());
    }

    @Test
    void aCompletedReturnIsIdempotentOnRetry() {
        Order order = fulfilledOrder();
        OrderReturn r = OrderReturn.requested(order.getId(), buyer,
                OrderReturn.ReturnType.RETURN, "wrong size", "SAR", Instant.now());
        r.approve(2_700L, Instant.now());
        r.receive(Instant.now());
        r.complete(null, Instant.now());
        when(returns.findById(r.getId())).thenReturn(Optional.of(r));

        OrderReturn completed = service.complete(r.getId());

        assertThat(completed.getStatus()).isEqualTo(OrderReturn.ReturnStatus.COMPLETED);
        verify(orderRefundPort, never()).refundForOrder(any(), anyLong());
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
