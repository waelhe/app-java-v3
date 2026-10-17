package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderDeliveryDetails;
import com.marketplace.shared.api.OrderFulfilledEvent;
import com.marketplace.shared.api.OrderPaymentPort;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrdersDeliveryPortTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductPricingPort productPricingPort;
    @Mock private ProductStockPort productStockPort;
    @Mock private OrderPaymentPort orderPaymentPort;
    @Mock private com.marketplace.shared.api.CommercialPricingPort commercialPricingPort;
    @Mock private OrderPriceComponentRepository orderPriceComponentRepository;
    @Mock private com.marketplace.shared.api.CheckoutDeliveryPort checkoutDeliveryPort;
    @Mock private Authentication authentication;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID deliveryRequestId = UUID.randomUUID();

    private OrdersService service() {
        return new OrdersService(cartRepository, cartItemRepository, orderRepository, orderItemRepository,
                currentUserProvider, eventPublisher, productPricingPort, productStockPort, orderPaymentPort,
                commercialPricingPort, orderPriceComponentRepository, checkoutDeliveryPort);
    }

    private Order confirmedOrder() {
        Order order = Order.placed(buyerId, 5_000L, "SAR", sellerId);
        order.confirm(Instant.now());
        return order;
    }

    @Test
    void requiresConfirmedOrderAndReturnsOnlyTheOrderOwnedByTheBuyer() {
        Order order = confirmedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThat(service().requireConfirmedOrderForDelivery(order.getId(), buyerId))
                .isEqualTo(new OrderDeliveryDetails(order.getId(), buyerId, sellerId, "SAR", null));
    }

    @Test
    void doesNotRevealAnOrderToAnotherBuyerWhenQuotingDelivery() {
        Order order = confirmedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().requireConfirmedOrderForDelivery(order.getId(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onlyConfirmedOrdersWithKnownSellersCanBeQuotedForDelivery() {
        Order placed = Order.placed(buyerId, 5_000L, "SAR", sellerId);
        when(orderRepository.findById(placed.getId())).thenReturn(Optional.of(placed));
        assertThatThrownBy(() -> service().requireConfirmedOrderForDelivery(placed.getId(), buyerId))
                .isInstanceOf(ConflictException.class);

        Order noSeller = Order.placed(buyerId, 5_000L, "SAR", null);
        noSeller.confirm(Instant.now());
        when(orderRepository.findById(noSeller.getId())).thenReturn(Optional.of(noSeller));
        assertThatThrownBy(() -> service().requireConfirmedOrderForDelivery(noSeller.getId(), buyerId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void attachesTheDeliveryRequestToTheConfirmedBuyerOrder() {
        Order order = confirmedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().attachDeliveryRequest(order.getId(), buyerId, deliveryRequestId);

        assertThat(order.getDeliveryRequestId()).isEqualTo(deliveryRequestId);
        verify(orderRepository).save(order);
    }

    @Test
    void refusesDeliveryAttachmentByAnotherBuyerOrToAnUnconfirmedOrder() {
        Order confirmed = confirmedOrder();
        when(orderRepository.findByIdForUpdate(confirmed.getId())).thenReturn(Optional.of(confirmed));
        assertThatThrownBy(() -> service().attachDeliveryRequest(
                confirmed.getId(), UUID.randomUUID(), deliveryRequestId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(orderRepository, never()).save(any(Order.class));

        Order placed = Order.placed(buyerId, 5_000L, "SAR", sellerId);
        when(orderRepository.findByIdForUpdate(placed.getId())).thenReturn(Optional.of(placed));
        assertThatThrownBy(() -> service().attachDeliveryRequest(placed.getId(), buyerId, deliveryRequestId))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void clearsOnlyTheMatchingDeliveryAssociationAndToleratesMissingOrder() {
        Order order = confirmedOrder();
        UUID otherRequest = UUID.randomUUID();
        order.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().clearDeliveryRequest(order.getId(), otherRequest);
        assertThat(order.getDeliveryRequestId()).isEqualTo(deliveryRequestId);

        service().clearDeliveryRequest(order.getId(), deliveryRequestId);
        assertThat(order.getDeliveryRequestId()).isNull();
        verify(orderRepository, org.mockito.Mockito.times(2)).save(order);

        UUID missingOrderId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(missingOrderId)).thenReturn(Optional.empty());
        service().clearDeliveryRequest(missingOrderId, deliveryRequestId);
        verify(orderRepository, org.mockito.Mockito.times(2)).save(any(Order.class));
    }

    @Test
    void deliveryCompletionFulfillsTheLinkedOrderAndPublishesExactlyOneEvent() {
        Order order = confirmedOrder();
        order.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().fulfillFromDelivery(order.getId(), deliveryRequestId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(order.getFulfilledAt()).isNotNull();
        assertThat(order.isStockReserved()).isFalse();
        verify(orderRepository).save(order);
        verify(eventPublisher).publishEvent(new OrderFulfilledEvent(order.getId(), buyerId));
        verifyNoInteractions(productStockPort);
    }

    @Test
    void deliveryCompletionCommitsReservedStockBeforeFinishingTheOrder() {
        UUID productId = UUID.randomUUID();
        Order order = confirmedOrder();
        order.attachDeliveryRequest(deliveryRequestId);
        order.markStockReserved();
        OrderItem frozenLine = OrderItem.snapshotOf(order.getId(),
                CartItem.of(UUID.randomUUID(), productId, null, 2, 500L, "SAR"));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(frozenLine));

        service().fulfillFromDelivery(order.getId(), deliveryRequestId);

        verify(productStockPort).commit(Map.of(productId, 2));
        // the dual-shelf law (#616 composed): a product-level-only order
        // commits the product shelf and calls the variant shelf with the
        // empty map — both shelves settle in the same transaction.
        verify(productStockPort).commitVariants(Map.of());
        assertThat(order.isStockReserved()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        verify(eventPublisher).publishEvent(new OrderFulfilledEvent(order.getId(), buyerId));
    }

    @Test
    void redeliveredCompletionForTheSameFulfilledOrderIsIdempotent() {
        Order order = confirmedOrder();
        order.attachDeliveryRequest(deliveryRequestId);
        order.fulfill(Instant.now());
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().fulfillFromDelivery(order.getId(), deliveryRequestId);

        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(orderItemRepository, productStockPort, eventPublisher);
    }

    @Test
    void completionRejectsAnUnlinkedOrNonConfirmedOrder() {
        Order order = confirmedOrder();
        order.attachDeliveryRequest(UUID.randomUUID());
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service().fulfillFromDelivery(order.getId(), deliveryRequestId))
                .isInstanceOf(ConflictException.class);

        Order placed = Order.placed(buyerId, 5_000L, "SAR", sellerId);
        placed.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(placed.getId())).thenReturn(Optional.of(placed));
        assertThatThrownBy(() -> service().fulfillFromDelivery(placed.getId(), deliveryRequestId))
                .isInstanceOf(ConflictException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void ordinaryOrderCancellationCannotRaceAnActiveDeliveryLifecycle() {
        Order order = confirmedOrder();
        order.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().cancel(order.getId(), "buyer changed mind"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("delivery lifecycle");
        verify(orderRepository, never()).save(any(Order.class));
        verify(eventPublisher, never()).publishEvent(any(OrderCancelledEvent.class));
        verifyNoInteractions(productStockPort);
    }

    @Test
    void ordinaryFulfillmentCannotBypassAnActiveDeliveryLifecycle() {
        Order order = confirmedOrder();
        order.markStockReserved();
        order.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().fulfill(order.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("delivery lifecycle");

        verify(orderRepository, never()).save(any(Order.class));
        verify(eventPublisher, never()).publishEvent(any(OrderFulfilledEvent.class));
        verifyNoInteractions(productStockPort);
    }

    @Test
    void reattachingTheSameDeliveryRequestIsSafe() {
        Order order = confirmedOrder();
        order.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().attachDeliveryRequest(order.getId(), buyerId, deliveryRequestId);

        assertThat(order.getDeliveryRequestId()).isEqualTo(deliveryRequestId);
        verify(orderRepository).save(order);
    }

    @Test
    void refusesReplacingAnExistingDeliveryAssociation() {
        Order order = confirmedOrder();
        order.attachDeliveryRequest(deliveryRequestId);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        UUID replacementRequestId = UUID.randomUUID();

        assertThatThrownBy(() -> service().attachDeliveryRequest(
                order.getId(), buyerId, replacementRequestId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has a delivery request");

        assertThat(order.getDeliveryRequestId()).isEqualTo(deliveryRequestId);
        verify(orderRepository, never()).save(any(Order.class));
    }


    // ------------------------------------------------------------------
    // The V224 checkout-integration reads (requireOrderFulfillment + gates)
    // ------------------------------------------------------------------

    @Test
    void requireOrderFulfillmentAnswersTheFrozenSelectionOfAConfirmedOrder() {
        Order order = Order.placedWithFulfillment(buyerId, "SAR", sellerId,
                3000L, 0, 0, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh downtown", 24.7136, 46.6753, 1500L);
        order.confirm(Instant.now());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        com.marketplace.shared.api.OrderFulfillmentSelection selection =
                service().requireOrderFulfillment(order.getId(), buyerId);

        assertThat(selection.platformDelivery()).isTrue();
        assertThat(selection.shippingAmountMinor()).isEqualTo(1500L);
        assertThat(selection.shipToAddress()).isEqualTo("Riyadh downtown");
        assertThat(selection.currency()).isEqualTo("SAR");
    }

    @Test
    void aCompletingNonFinalLegHoldsTheOrderOpen() {
        Order order = Order.placedWithFulfillment(buyerId, "SAR", sellerId,
                3000L, 0, 0, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh downtown", 24.7136, 46.6753, 1500L);
        order.confirm(Instant.now());
        UUID leg = UUID.randomUUID();
        order.attachDeliveryRequest(leg);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(checkoutDeliveryPort.remainingActiveLegs(order.getId())).thenReturn(1L);

        service().fulfillFromDelivery(order.getId(), leg);

        // the multi-leg law: a sibling leg still flies — the order waits
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void theLastCompletingLegFulfillsTheOrder() {
        Order order = Order.placedWithFulfillment(buyerId, "SAR", sellerId,
                3000L, 0, 0, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh downtown", 24.7136, 46.6753, 1500L);
        order.confirm(Instant.now());
        UUID leg = UUID.randomUUID();
        order.attachDeliveryRequest(leg);
        order.markStockReserved();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());
        when(checkoutDeliveryPort.remainingActiveLegs(order.getId())).thenReturn(0L);

        service().fulfillFromDelivery(order.getId(), leg);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        verify(productStockPort).commit(java.util.Map.of());
        verify(productStockPort).commitVariants(java.util.Map.of());
    }

    @Test
    void anInFlightPlatformDeliveryBlocksTheOrderCancellation() {
        Order order = Order.placedWithFulfillment(buyerId, "SAR", sellerId,
                3000L, 0, 0, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh downtown", 24.7136, 46.6753, 1500L);
        order.confirm(Instant.now());
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(checkoutDeliveryPort.remainingActiveLegs(order.getId())).thenReturn(2L);

        assertThatThrownBy(() -> service().cancel(order.getId(), "changed mind"))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("in-flight platform delivery");
    }

    @Test
    void theNonPrimaryLegCompletesTheOrderWhenItIsTheLastOne() {
        Order order = Order.placedWithFulfillment(buyerId, "SAR", sellerId,
                3000L, 0, 0, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh downtown", 24.7136, 46.6753, 1500L);
        order.confirm(Instant.now());
        UUID primaryLeg = UUID.randomUUID();
        UUID splitLeg = UUID.randomUUID();
        order.attachDeliveryRequest(primaryLeg);
        order.markStockReserved();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());
        // the primary was delivered first and the SPLIT leg is the last one —
        // its id never rode the order's primary link (the review's Major
        // finding: the link equality must not rule the platform path)
        when(checkoutDeliveryPort.remainingActiveLegs(order.getId())).thenReturn(0L);

        service().fulfillFromDelivery(order.getId(), splitLeg);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        verify(productStockPort).commit(java.util.Map.of());
        verify(productStockPort).commitVariants(java.util.Map.of());
    }

    @Test
    void aLegCompletionAfterThePrimaryWasCancelledFulfillsTheOrder() {
        Order order = Order.placedWithFulfillment(buyerId, "SAR", sellerId,
                3000L, 0, 0, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh downtown", 24.7136, 46.6753, 1500L);
        order.confirm(Instant.now());
        order.markStockReserved();
        // the primary's cancellation cleared the link (clearDeliveryRequest
        // nulls it) — the sibling leg's DELIVERED event is the order's last
        // one; the partial-cancellation law: the order completes for its
        // remaining legs, never stuck CONFIRMED on a dangling link check
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());
        when(checkoutDeliveryPort.remainingActiveLegs(order.getId())).thenReturn(0L);

        service().fulfillFromDelivery(order.getId(), UUID.randomUUID());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
    }
}
