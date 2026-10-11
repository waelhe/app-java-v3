package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderPaymentPort;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-11 unit gate — the machine's own invariants (the declared gate's first
 * half; the journey IT carries the second on the real chain):
 * <ul>
 *   <li>placement: the snapshot path (cart lines → frozen order lines →
 *       derived total), the cart tombstone, and the PLACED-no-event
 *       discipline (the A-03 lesson — an event without a listener is a
 *       measured defect).</li>
 *   <li>the legal edges: confirm from PLACED, fulfill from CONFIRMED,
 *       cancel from the open states — each publishing its event.</li>
 *   <li>the illegal edges: the terminal states do not reopen, skipping a
 *       state does not work — every one answers the house 409 contract.</li>
 *   <li>the privacy contract: an honest 404 to the non-buyer.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrdersServiceTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProductPricingPort productPricingPort;
    @Mock
    private ProductStockPort productStockPort;
    @Mock
    private OrderPaymentPort orderPaymentPort;
    @Mock
    private Authentication authentication;

    private OrdersService service;

    private final UUID consumer = UUID.randomUUID();

    /**
     * Stage 6 (ADR-0002): the placement tests' pricing stub — every line's
     * product prices ACTIVE under one seller, so the single-seller and
     * state gates pass and the stock seam is the only remaining edge.
     */
    private void priceActive(UUID productId) {
        when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, seller, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
    }

    private final UUID seller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OrdersService(cartRepository, cartItemRepository,
                orderRepository, orderItemRepository, currentUserProvider, eventPublisher,
                productPricingPort, productStockPort, orderPaymentPort);
    }

    // ------------------------------------------------------------------
    // Placement: the snapshot path
    // ------------------------------------------------------------------

    @Test
    void placementFreezesTheCartLinesIntoTheOrderAndTombstonesTheCart() {
        Cart cart = Cart.activeFor(consumer);
        CartItem line = CartItem.of(cart.getId(), UUID.randomUUID(), 2, 1500L, "SAR");
        CartItem line2 = CartItem.of(cart.getId(), UUID.randomUUID(), 1, 9900L, "SAR");
        priceActive(line.getProductId());
        when(productPricingPort.priceOf(line2.getProductId())).thenReturn(
                new ProductPricingPort.ProductPrice(line2.getProductId(), seller, 9900L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(line, line2));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        java.util.List<OrderDetail> placed = service.place(consumer);
        Order order = placed.get(0).order();

        assertThat(placed).as("the single-seller cart places as one order").hasSize(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        // The derived total: 2 × 1500 + 1 × 9900 — never caller-supplied.
        assertThat(order.getTotalAmountMinor()).isEqualTo(12900L);
        assertThat(order.getCurrency()).isEqualTo("SAR");
        assertThat(cart.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
        assertThat(cart.getCheckedOutAt()).isNotNull();

        ArgumentCaptor<OrderItem> frozen = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, org.mockito.Mockito.times(2)).save(frozen.capture());
        assertThat(frozen.getAllValues())
                .extracting(OrderItem::getProductId)
                .containsExactlyInAnyOrder(line.getProductId(), line2.getProductId());
        assertThat(frozen.getAllValues())
                .allSatisfy(item -> {
                    assertThat(item.getOrderId()).isEqualTo(order.getId());
                    assertThat(item.getQuantity()).isNotNull();
                    assertThat(item.getUnitAmountMinor()).isNotNull();
                });
    }

    @Test
    void placementPublishesNothingPlacedCarriesNoEvent() {
        Cart cart = Cart.activeFor(consumer);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, 1, 100L, "SAR")));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.place(consumer);

        // The A-03 discipline: PLACED is the buyer's own intra-module write —
        // an event without a listener is a measured defect.
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void placementFromAnEmptyCartAnswersThe409Contract() {
        Cart cart = Cart.activeFor(consumer);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.place(consumer))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void placementFromAnAbsentCartAnswersThe409Contract() {
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(consumer))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void placementRejectsAStaleCurrencyLine() {
        // The ADR-0002 re-pricing: the line carries what the add resolved;
        // the product NOW prices in another currency — the placement
        // refuses (re-add the line) instead of freezing a mixed record.
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                CartItem.of(cart.getId(), productId, 1, 100L, "SAR")));
        when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, seller, 100L, "USD",
                        ProductPricingPort.StorefrontState.ACTIVE));

        assertThatThrownBy(() -> service.place(consumer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("re-add the line");
    }

    /**
     * ADR-0010 (the ADR-0002 deferral opened): the mixed-seller cart SPLITS
     * into one order per seller — each order carries its own seller, its
     * own derived total and its own frozen lines; ONE whole-cart reserve
     * (all-or-nothing across the groups), the cart tombstones once, and
     * PLACED still publishes nothing.
     */
    @Test
    void placementSplitsAMixedSellerCartIntoOneOrderPerSeller() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID p1 = java.util.UUID.randomUUID();
        java.util.UUID p2 = java.util.UUID.randomUUID();
        java.util.UUID sellerA = UUID.randomUUID();
        java.util.UUID sellerB = UUID.randomUUID();
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                CartItem.of(cart.getId(), p1, 2, 1500L, "SAR"),
                CartItem.of(cart.getId(), p2, 1, 9900L, "SAR")));
        when(productPricingPort.priceOf(p1)).thenReturn(
                new ProductPricingPort.ProductPrice(p1, sellerA, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(productPricingPort.priceOf(p2)).thenReturn(
                new ProductPricingPort.ProductPrice(p2, sellerB, 9900L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<OrderDetail> placed = service.place(consumer);

        assertThat(placed).as("one order per seller").hasSize(2);
        Order first = placed.get(0).order();
        Order second = placed.get(1).order();
        // First-seen group order: p1's seller owns the first order.
        assertThat(first.getSellerId()).isEqualTo(sellerA);
        assertThat(first.getTotalAmountMinor()).isEqualTo(3000L);
        assertThat(second.getSellerId()).isEqualTo(sellerB);
        assertThat(second.getTotalAmountMinor()).isEqualTo(9900L);
        // ONE whole-cart reserve: all lines, all-or-nothing across groups.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<UUID, Integer>> reserved =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(productStockPort).reserve(reserved.capture());
        assertThat(reserved.getValue()).containsEntry(p1, 2).containsEntry(p2, 1);
        // The cart tombstones ONCE; PLACED still publishes nothing.
        assertThat(cart.getStatus()).isEqualTo(CartStatus.CHECKED_OUT);
        verify(eventPublisher, never()).publishEvent(any());
        // Each order's frozen lines ride its own order id.
        ArgumentCaptor<OrderItem> frozen = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, org.mockito.Mockito.times(2)).save(frozen.capture());
        assertThat(frozen.getAllValues().get(0).getOrderId()).isEqualTo(first.getId());
        assertThat(frozen.getAllValues().get(1).getOrderId()).isEqualTo(second.getId());
    }

    /** One currency per order: a seller group carrying mixed currencies answers the honest 409. */
    @Test
    void aSellerGroupWithMixedCurrenciesAnswersThe409() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID p1 = java.util.UUID.randomUUID();
        java.util.UUID p2 = java.util.UUID.randomUUID();
        java.util.UUID sellerA = UUID.randomUUID();
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                CartItem.of(cart.getId(), p1, 1, 100L, "SAR"),
                CartItem.of(cart.getId(), p2, 1, 100L, "USD")));
        when(productPricingPort.priceOf(p1)).thenReturn(
                new ProductPricingPort.ProductPrice(p1, sellerA, 100L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(productPricingPort.priceOf(p2)).thenReturn(
                new ProductPricingPort.ProductPrice(p2, sellerA, 100L, "USD",
                        ProductPricingPort.StorefrontState.ACTIVE));

        assertThatThrownBy(() -> service.place(consumer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("one currency per order");
        verify(productStockPort, never()).reserve(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void placementReservesTheLinesStockAndFlagsTheOrder() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, 2, 1500L, "SAR")));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OrderDetail detail = service.place(consumer).get(0);

        org.mockito.ArgumentCaptor<java.util.Map<java.util.UUID, Integer>> reserved =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(productStockPort).reserve(reserved.capture());
        assertThat(reserved.getValue()).containsEntry(productId, 2);
        assertThat(detail.order().isStockReserved()).isTrue();
    }

    @Test
    void placementRefusedByTheStockSeamRollsTheWholeOrderBack() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, 2, 1500L, "SAR")));
        org.mockito.Mockito.doThrow(new ConflictException("cannot reserve"))
                .when(productStockPort).reserve(any());

        assertThatThrownBy(() -> service.place(consumer))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // The legal edges (each publishing its event)
    // ------------------------------------------------------------------

    @Test
    void confirmMovesPlacedToConfirmedAndPublishes() {
        Order order = placedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order confirmed = service.confirm(order.getId());

        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new OrderConfirmedEvent(order.getId(), consumer));
    }

    @Test
    void fulfillMovesConfirmedToFulfilledAndPublishes() {
        Order order = placedOrder();
        order.confirm(java.time.Instant.now());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order fulfilled = service.fulfill(order.getId());

        assertThat(fulfilled.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(fulfilled.getFulfilledAt()).isNotNull();
        verify(eventPublisher).publishEvent(new OrderFulfilledEvent(order.getId(), consumer));
    }

    @Test
    void cancelFromPlacedIsLegalAndPublishesWithTheReason() {
        Order order = placedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order cancelled = service.cancel(order.getId(), "buyer changed mind");

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo("buyer changed mind");
        verify(eventPublisher).publishEvent(
                new OrderCancelledEvent(order.getId(), consumer, "buyer changed mind"));
    }

    @Test
    void cancelFromConfirmedIsAlsoLegal() {
        Order order = placedOrder();
        order.confirm(java.time.Instant.now());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.cancel(order.getId(), "late cancel").getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    // ------------------------------------------------------------------
    // The illegal edges (the house 409 contract)
    // ------------------------------------------------------------------

    @Test
    void confirmFromConfirmedAnswers409() {
        Order order = placedOrder();
        order.confirm(java.time.Instant.now());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirm(order.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("confirm");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void fulfillFromPlacedAnswers409TheMachineSkipsNoState() {
        Order order = placedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.fulfill(order.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("fulfill");
    }

    @Test
    void fulfilledIsTerminalCancelAndFulfillBothAnswer409() {
        Order order = placedOrder();
        order.confirm(java.time.Instant.now());
        order.fulfill(java.time.Instant.now());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(order.getId(), "too late"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.fulfill(order.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelledIsTerminalConfirmAnswers409() {
        Order order = placedOrder();
        order.cancel("done", java.time.Instant.now());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirm(order.getId()))
                .isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------
    // The privacy contract
    // ------------------------------------------------------------------

    @Test
    void theNonBuyerGetsAnHonest404() {
        Order order = placedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID stranger = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(stranger);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrderForUser(order.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theBuyerReadsTheirOwnOrderAndTheAdminToo() {
        Order order = placedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumer);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        assertThat(service.getOrderForUser(order.getId(), authentication)).isSameAs(order);

        UUID admin = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(admin);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertThat(service.getOrderForUser(order.getId(), authentication)).isSameAs(order);
    }

    @Test
    void theStrangersHistoryPageIsAnHonest404Too() {
        UUID strangerListing = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumer);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.listByConsumer(strangerListing, Pageable.ofSize(10), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // The cart union semantics
    // ------------------------------------------------------------------

    @Test
    void addingAnExistingProductRaisesItsQuantityInsteadOfASecondLine() {
        Cart cart = Cart.activeFor(consumer);
        UUID product = UUID.randomUUID();
        CartItem existing = CartItem.of(cart.getId(), product, 1, 500L, "SAR");
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndProductId(cart.getId(), product))
                .thenReturn(Optional.of(existing));

        when(productPricingPort.priceOf(product)).thenReturn(
                new ProductPricingPort.ProductPrice(product, seller, 500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));

        CartItem result = service.addCartItem(consumer, product, 2);

        assertThat(result.getQuantity()).isEqualTo(3);
        verify(cartItemRepository, never()).save(any());
    }

    private Order placedOrder() {
        // Order.placed generates its own id (UUID.randomUUID()) — no
        // reflection needed; the helper exists for the one read that
        // matters: a fresh PLACED machine state.
        return Order.placed(consumer, 5000L, "SAR", null);
    }
}
