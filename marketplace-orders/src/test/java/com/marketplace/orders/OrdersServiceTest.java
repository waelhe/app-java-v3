package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderPaymentPort;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.orders.spi.OrderBulkCancelResult;
import com.marketplace.orders.spi.OrderView;
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
import static org.mockito.Mockito.inOrder;
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
    private com.marketplace.shared.api.CommercialPricingPort commercialPricingPort;
    @Mock
    private OrderPriceComponentRepository orderPriceComponentRepository;
    @Mock
    private com.marketplace.shared.api.CheckoutDeliveryPort checkoutDeliveryPort;
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
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, seller, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        resolveActive(productId, 1500L, "SAR");
    }

    /**
     * The Phase-3 placement stub: the commercial policy layer resolves the
     * unit ACTIVE at the catalog price (no override) — the placement's
     * re-pricing read.
     */
    private void resolveActive(UUID productId, long priceMinor, String currency) {
        when(commercialPricingPort.resolveUnitPrice(productId, null)).thenReturn(
                new com.marketplace.shared.api.CommercialPricingPort.ResolvedUnitPrice(
                        priceMinor, currency, seller, true, null, null));
    }

    private void resolveVariantActive(UUID product, UUID variant, long priceMinor) {
        when(commercialPricingPort.resolveUnitPrice(product, variant)).thenReturn(
                new com.marketplace.shared.api.CommercialPricingPort.ResolvedUnitPrice(
                        priceMinor, "SAR", seller, true, null, null));
        org.mockito.Mockito.lenient().when(productPricingPort.priceOfVariant(variant)).thenReturn(
                new ProductPricingPort.VariantPrice(variant, product, seller, priceMinor, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.ACTIVE));
    }

    /** The Phase-3 no-policies breakdown: subtotal stands, no discounts, no tax. */
    private com.marketplace.shared.api.CommercialPricingPort.CommercialBreakdown noPolicies(
            long subtotalMinor) {
        return new com.marketplace.shared.api.CommercialPricingPort.CommercialBreakdown(
                subtotalMinor, 0, null, null, 0, null, null, 0, null, true, 0, subtotalMinor);
    }

    private final UUID seller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OrdersService(cartRepository, cartItemRepository,
                orderRepository, orderItemRepository, currentUserProvider, eventPublisher,
                productPricingPort, productStockPort, orderPaymentPort,
                commercialPricingPort, orderPriceComponentRepository, checkoutDeliveryPort);
        org.mockito.Mockito.lenient()
                .when(commercialPricingPort.computeBreakdown(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> noPolicies(inv.getArgument(1)));
        org.mockito.Mockito.lenient()
                .when(orderPriceComponentRepository.save(any(OrderPriceComponent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------
    // Placement: the snapshot path
    // ------------------------------------------------------------------

    @Test
    void placementFreezesTheCartLinesIntoTheOrderAndTombstonesTheCart() {
        Cart cart = Cart.activeFor(consumer);
        CartItem line = CartItem.of(cart.getId(), UUID.randomUUID(), null, 2, 1500L, "SAR");
        CartItem line2 = CartItem.of(cart.getId(), UUID.randomUUID(), null, 1, 9900L, "SAR");
        priceActive(line.getProductId());
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(line2.getProductId())).thenReturn(
                new ProductPricingPort.ProductPrice(line2.getProductId(), seller, 9900L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        resolveActive(line2.getProductId(), 9900L, "SAR");
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId())).thenReturn(List.of(line, line2));
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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 1, 100L, "SAR")));
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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId())).thenReturn(List.of());

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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId())).thenReturn(List.of(
                CartItem.of(cart.getId(), productId, null, 1, 100L, "SAR")));
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, seller, 100L, "USD",
                        ProductPricingPort.StorefrontState.ACTIVE));
        resolveActive(productId, 100L, "USD");

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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId())).thenReturn(List.of(
                CartItem.of(cart.getId(), p1, null, 2, 1500L, "SAR"),
                CartItem.of(cart.getId(), p2, null, 1, 9900L, "SAR")));
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(p1)).thenReturn(
                new ProductPricingPort.ProductPrice(p1, sellerA, 1500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(p2)).thenReturn(
                new ProductPricingPort.ProductPrice(p2, sellerB, 9900L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(commercialPricingPort.resolveUnitPrice(p1, null)).thenReturn(
                new com.marketplace.shared.api.CommercialPricingPort.ResolvedUnitPrice(
                        1500L, "SAR", sellerA, true, null, null));
        when(commercialPricingPort.resolveUnitPrice(p2, null)).thenReturn(
                new com.marketplace.shared.api.CommercialPricingPort.ResolvedUnitPrice(
                        9900L, "SAR", sellerB, true, null, null));
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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId())).thenReturn(List.of(
                CartItem.of(cart.getId(), p1, null, 1, 100L, "SAR"),
                CartItem.of(cart.getId(), p2, null, 1, 100L, "USD")));
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(p1)).thenReturn(
                new ProductPricingPort.ProductPrice(p1, sellerA, 100L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
        org.mockito.Mockito.lenient().when(productPricingPort.priceOf(p2)).thenReturn(
                new ProductPricingPort.ProductPrice(p2, sellerA, 100L, "USD",
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(commercialPricingPort.resolveUnitPrice(p1, null)).thenReturn(
                new com.marketplace.shared.api.CommercialPricingPort.ResolvedUnitPrice(
                        100L, "SAR", sellerA, true, null, null));
        when(commercialPricingPort.resolveUnitPrice(p2, null)).thenReturn(
                new com.marketplace.shared.api.CommercialPricingPort.ResolvedUnitPrice(
                        100L, "USD", sellerA, true, null, null));

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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 2, 1500L, "SAR")));
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
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 2, 1500L, "SAR")));
        org.mockito.Mockito.doThrow(new ConflictException("cannot reserve"))
                .when(productStockPort).reserve(any());

        assertThatThrownBy(() -> service.place(consumer))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // The Phase-3 commercial breakdown (the frozen agreement)
    // ------------------------------------------------------------------

    @Test
    void placementFreezesTheCommercialBreakdownAndItsComponents() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        // two units at 1500 = subtotal 3000
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 2, 1500L, "SAR")));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        // the pricing module's answer for THIS placement: 10% promotion
        // (300) + a 500 voucher on the post-promotion 2700 + an INCLUSIVE
        // 15% tax component on the 2200 discounted subtotal
        UUID promotionId = UUID.randomUUID();
        UUID voucherId = UUID.randomUUID();
        when(commercialPricingPort.computeBreakdown(consumer, 3000L, "SAR", "RAMADAN10"))
                .thenReturn(new com.marketplace.shared.api.CommercialPricingPort.CommercialBreakdown(
                        3000L, 300L, promotionId, "Ramadan 10%", 500L, voucherId, "RAMADAN10",
                        287L, "SA", true, 1500, 2200L));

        List<OrderDetail> placed = service.place(consumer, "RAMADAN10");

        Order order = placed.get(0).order();
        // the frozen agreement: subtotal 3000, discount 800 (promotion +
        // voucher), INCLUSIVE tax 287 contained in the 2200 total
        assertThat(order.getSubtotalAmountMinor()).isEqualTo(3000L);
        assertThat(order.getDiscountAmountMinor()).isEqualTo(800L);
        assertThat(order.getTaxAmountMinor()).isEqualTo(287L);
        assertThat(order.getTotalAmountMinor()).isEqualTo(2200L);
        // the itemized components — promotion, voucher, tax — each with its
        // reference and amount (the §3.4 auditable breakdown)
        ArgumentCaptor<OrderPriceComponent> component = ArgumentCaptor.forClass(OrderPriceComponent.class);
        verify(orderPriceComponentRepository, org.mockito.Mockito.times(3)).save(component.capture());
        assertThat(component.getAllValues()).extracting(OrderPriceComponent::getComponentType)
                .containsExactly(OrderPriceComponent.ComponentType.PROMOTION,
                        OrderPriceComponent.ComponentType.VOUCHER,
                        OrderPriceComponent.ComponentType.TAX);
        assertThat(component.getAllValues().get(2).getReferenceCode())
                .isEqualTo("SA:INCLUSIVE:1500");
        // the voucher settlement rode INSIDE the placement transaction
        verify(commercialPricingPort).settleVoucher(order.getId(), consumer, "RAMADAN10", 500L, "SAR");
    }

    @Test
    void aRefusedVoucherSettlementRollsTheWholePlacementBack() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 2, 1500L, "SAR")));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(commercialPricingPort.computeBreakdown(org.mockito.ArgumentMatchers.eq(consumer),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("EXPIRED")))
                .thenReturn(new com.marketplace.shared.api.CommercialPricingPort.CommercialBreakdown(
                        3000L, 0, null, null, 300L, UUID.randomUUID(), "EXPIRED",
                        0, null, true, 0, 2700L));
        org.mockito.Mockito.doThrow(new ConflictException("Voucher EXPIRED has reached its "
                + "redemption limit"))
                .when(commercialPricingPort).settleVoucher(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any());

        // the whole placement rolls back — the cart keeps its lines
        assertThatThrownBy(() -> service.place(consumer, "EXPIRED"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("redemption limit");
    }

    // ------------------------------------------------------------------
    // The V224 checkout fulfillment selection (the frozen agreement)
    // ------------------------------------------------------------------

    @Test
    void aPlatformDeliverySelectionFreezesTheFareAndTheDestinationIntoTheOrder() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 2, 1500L, "SAR")));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkoutDeliveryPort.fareFor(seller, 24.7136, 46.6753)).thenReturn(
                new com.marketplace.shared.api.CheckoutDeliveryPort.DeliveryFareOption(
                        true, 1500L, 1800L, "SAR", null));

        List<OrderDetail> placed = service.place(consumer, null, new OrdersService.FulfillmentSelection(
                FulfillmentMethod.PLATFORM_DELIVERY, "Riyadh downtown", 24.7136, 46.6753));

        Order order = placed.get(0).order();
        // the frozen selection: method, fee INSIDE the total, destination
        assertThat(order.getFulfillmentMethod()).isEqualTo(FulfillmentMethod.PLATFORM_DELIVERY);
        assertThat(order.getShippingAmountMinor()).isEqualTo(1500L);
        assertThat(order.getShipToAddress()).isEqualTo("Riyadh downtown");
        // subtotal 3000 (2 × 1500) + shipping 1500 = 4500
        assertThat(order.getTotalAmountMinor()).isEqualTo(4500L);
        verify(checkoutDeliveryPort).fareFor(seller, 24.7136, 46.6753);
    }

    @Test
    void anUnserviceableSellerGroupRefusesTheWholePlacement() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 2, 1500L, "SAR")));
        when(checkoutDeliveryPort.fareFor(seller, 40.0, 50.0)).thenReturn(
                com.marketplace.shared.api.CheckoutDeliveryPort.DeliveryFareOption
                        .unserviceable("the route is outside the configured delivery area"));

        assertThatThrownBy(() -> service.place(consumer, null, new OrdersService.FulfillmentSelection(
                FulfillmentMethod.PLATFORM_DELIVERY, "Far away", 40.0, 50.0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no platform delivery");
        verify(orderRepository, never()).save(any());
        verify(productStockPort, never()).reserve(any());
    }

    @Test
    void aPickupSelectionFreezesTheMethodWithNoFeeOrDestination() {
        Cart cart = Cart.activeFor(consumer);
        java.util.UUID productId = java.util.UUID.randomUUID();
        priceActive(productId);
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId()))
                .thenReturn(List.of(CartItem.of(cart.getId(), productId, null, 1, 1500L, "SAR")));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        List<OrderDetail> placed = service.place(consumer, null,
                new OrdersService.FulfillmentSelection(FulfillmentMethod.PICKUP, null, null, null));

        Order order = placed.get(0).order();
        assertThat(order.getFulfillmentMethod()).isEqualTo(FulfillmentMethod.PICKUP);
        assertThat(order.getShippingAmountMinor()).isZero();
        assertThat(order.getShipToAddress()).isNull();
        assertThat(order.getTotalAmountMinor()).isEqualTo(1500L);
        verify(checkoutDeliveryPort, never()).fareFor(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void aPlatformDeliveryWithoutADestinationAnswersTheHonest400() {
        assertThatThrownBy(() -> service.place(consumer, null, new OrdersService.FulfillmentSelection(
                FulfillmentMethod.PLATFORM_DELIVERY, null, null, null)))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("complete destination");
    }

    // ------------------------------------------------------------------
    // The legal edges (each publishing its event)
    // ------------------------------------------------------------------

    @Test
    void confirmMovesPlacedToConfirmedAndPublishes() {
        Order order = placedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
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
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order fulfilled = service.fulfill(order.getId());

        assertThat(fulfilled.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(fulfilled.getFulfilledAt()).isNotNull();
        verify(eventPublisher).publishEvent(new OrderFulfilledEvent(order.getId(), consumer));
    }

    @Test
    void cancelFromPlacedIsLegalAndPublishesWithTheReason() {
        Order order = placedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
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
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
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
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirm(order.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("confirm");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void fulfillFromPlacedAnswers409TheMachineSkipsNoState() {
        Order order = placedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.fulfill(order.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("fulfill");
    }

    @Test
    void fulfilledIsTerminalCancelAndFulfillBothAnswer409() {
        Order order = placedOrder();
        order.confirm(java.time.Instant.now());
        order.fulfill(java.time.Instant.now());
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(order.getId(), "too late"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.fulfill(order.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelledIsTerminalConfirmAnswers409() {
        Order order = placedOrder();
        order.cancel("done", java.time.Instant.now());
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

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
    // The merchant surface (the Saleor study's Phase-1 slice — the
    // seller scope, the honest 404, and the post-command read-back)
    // ------------------------------------------------------------------

    @Test
    void theSellerReadsTheirOwnOrderDetailAndTheAdminToo() {
        Order order = soldOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        OrderDetail detail = service.getOrderDetailForSeller(order.getId(), authentication);
        assertThat(detail.order()).isSameAs(order);

        UUID admin = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(admin);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertThat(service.getOrderDetailForSeller(order.getId(), authentication).order())
                .isSameAs(order);
    }

    @Test
    void theBuyerAndTheStrangerGetTheHonest404OnTheSellerSurface() {
        Order order = soldOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        // The BUYER is not the seller: the merchant surface is not theirs.
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumer);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrderDetailForSeller(order.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aLegacySellerlessOrderAuthorizesAdminOnly() {
        Order order = placedOrder(); // sellerId = null — the V184 legacy row
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumer);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrderDetailForSeller(order.getId(), authentication))
                .as("a null-seller row has no seller to authorize — not even the buyer")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listSoldByCallerAssemblesThePageWithOneItemsHop() {
        Order order = soldOrder();
        OrderItem line = OrderItem.snapshotOf(order.getId(),
                CartItem.of(Cart.activeFor(consumer).getId(), UUID.randomUUID(), null, 1, 5000L, "SAR"));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(orderRepository.findBySellerIdOrderByPlacedAtDescIdDesc(
                seller, Pageable.ofSize(10)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(order)));
        when(orderItemRepository.findByOrderIdIn(List.of(order.getId())))
                .thenReturn(List.of(line));

        org.springframework.data.domain.Page<OrderDetail> page =
                service.listSoldByCaller(authentication, Pageable.ofSize(10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).items()).containsExactly(line);
    }

    @Test
    void listSoldByCallerAnswersTheHonestEmptyPage() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(orderRepository.findBySellerIdOrderByPlacedAtDescIdDesc(
                seller, Pageable.ofSize(10)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        assertThat(service.listSoldByCaller(authentication, Pageable.ofSize(10)).getContent())
                .isEmpty();
    }

    @Test
    void confirmForSellerMovesTheMachineAndAnswersTheReadBack() {
        Order order = soldOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        // No isAdmin stub: the seller match short-circuits the gate — the
        // admin branch never runs for the matching seller (strict stubs).
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());

        OrderDetail confirmed = service.confirmForSeller(order.getId(), authentication);

        assertThat(confirmed.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.order().getConfirmedAt()).isNotNull();
        verify(eventPublisher).publishEvent(new OrderConfirmedEvent(order.getId(), consumer));
    }

    @Test
    void fulfillForSellerCommitsTheStockAndAnswersTheReadBack() {
        Order order = soldOrder();
        order.confirm(java.time.Instant.now());
        order.markStockReserved(); // the placement's own flag (the place() edge sets it)
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        // No isAdmin stub: the seller match short-circuits the gate.
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());

        OrderDetail fulfilled = service.fulfillForSeller(order.getId(), authentication);

        assertThat(fulfilled.order().getStatus()).isEqualTo(OrderStatus.FULFILLED);
        verify(productStockPort).commit(any());
        verify(eventPublisher).publishEvent(new OrderFulfilledEvent(order.getId(), consumer));
    }

    @Test
    void cancelForSellerReleasesTheStockAndPublishesWithTheReason() {
        Order order = soldOrder();
        order.markStockReserved(); // the placement's own flag (the place() edge sets it)
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        // No isAdmin stub: the seller match short-circuits the gate.
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());

        OrderDetail cancelled = service.cancelForSeller(order.getId(), "out of stock", authentication);

        assertThat(cancelled.order().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.order().getCancelReason()).isEqualTo("out of stock");
        verify(productStockPort).release(any());
        verify(eventPublisher).publishEvent(
                new OrderCancelledEvent(order.getId(), consumer, "out of stock"));
    }

    @Test
    void aForeignCallerCannotPullTheMerchantLevers() {
        Order order = soldOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.confirmForSeller(order.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.fulfillForSeller(order.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancelForSeller(order.getId(), "no", authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        // The guard fired BEFORE the machine: no save, no event, no stock.
        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(productStockPort, never()).release(any());
    }

    @Test
    void theIllegalMerchantEdgesAnswerTheHouse409() {
        Order order = soldOrder();
        order.confirm(java.time.Instant.now()); // CONFIRMED: confirm is illegal now
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        // No isAdmin stub: the seller match short-circuits the gate — the
        // 409 comes from the machine's own edge, past the scope check.

        assertThatThrownBy(() -> service.confirmForSeller(order.getId(), authentication))
                .isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------
    // The cart union semantics
    // ------------------------------------------------------------------

    @Test
    void addingAnExistingProductRaisesItsQuantityInsteadOfASecondLine() {
        Cart cart = Cart.activeFor(consumer);
        UUID product = UUID.randomUUID();
        CartItem existing = CartItem.of(cart.getId(), product, null, 1, 500L, "SAR");
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndProductIdAndVariantIdIsNull(cart.getId(), product))
                .thenReturn(Optional.of(existing));

        when(productPricingPort.priceOf(product)).thenReturn(
                new ProductPricingPort.ProductPrice(product, seller, 500L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));

        CartItem result = service.addCartItem(consumer, product, null, 2);

        assertThat(result.getQuantity()).isEqualTo(3);
        verify(cartItemRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // ADR-0012 (the cart-integration slice): the variant lines' unit gates
    // ------------------------------------------------------------------

    @Test
    void addingAVariantLinePricesFromTheVariantsOwnAuthoritativeRecord() {
        Cart cart = Cart.activeFor(consumer);
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(productPricingPort.priceOfVariant(variant)).thenReturn(
                new ProductPricingPort.VariantPrice(variant, product, seller, 1750L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(cartItemRepository.findByCartIdAndProductIdAndVariantId(cart.getId(), product, variant))
                .thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        CartItem line = service.addCartItem(consumer, product, variant, 1);

        assertThat(line.getVariantId()).isEqualTo(variant);
        assertThat(line.getProductId()).isEqualTo(product);
        assertThat(line.getUnitAmountMinor()).isEqualTo(1750L);
        assertThat(line.getCurrency()).isEqualTo("SAR");
        // the product-level pricing read is never consulted for a variant line
        verify(productPricingPort, never()).priceOf(any());
    }

    @Test
    void addingAVariantFromAnotherProductAnswersTheHonest400() {
        UUID product = UUID.randomUUID();
        UUID foreignVariant = UUID.randomUUID();
        when(productPricingPort.priceOfVariant(foreignVariant)).thenReturn(
                new ProductPricingPort.VariantPrice(foreignVariant, UUID.randomUUID(), seller,
                        100L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.ACTIVE));

        assertThatThrownBy(() -> service.addCartItem(consumer, product, foreignVariant, 1))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("belongs to product");
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void addingASuspendedVariantUnderAnActiveProductAnswersTheHouse409() {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        when(productPricingPort.priceOfVariant(variant)).thenReturn(
                new ProductPricingPort.VariantPrice(variant, product, seller, 100L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.SUSPENDED));

        assertThatThrownBy(() -> service.addCartItem(consumer, product, variant, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not purchasable");
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void addingTheSameVariantAgainRaisesItsQuantityInsteadOfASecondLine() {
        Cart cart = Cart.activeFor(consumer);
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        CartItem existing = CartItem.of(cart.getId(), product, variant, 1, 1750L, "SAR");
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(productPricingPort.priceOfVariant(variant)).thenReturn(
                new ProductPricingPort.VariantPrice(variant, product, seller, 1750L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE,
                        ProductPricingPort.StorefrontState.ACTIVE));
        when(cartItemRepository.findByCartIdAndProductIdAndVariantId(cart.getId(), product, variant))
                .thenReturn(Optional.of(existing));

        CartItem result = service.addCartItem(consumer, product, variant, 2);

        assertThat(result.getQuantity()).isEqualTo(3);
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void placementFreezesTheVariantLineAndReservesTheVariantShelf() {
        Cart cart = Cart.activeFor(consumer);
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        CartItem line = CartItem.of(cart.getId(), product, variant, 2, 1700L, "SAR");
        when(cartRepository.findByConsumerIdAndStatus(consumer, CartStatus.ACTIVE))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdOrderByCreatedAtAscIdAsc(cart.getId())).thenReturn(List.of(line));
        resolveVariantActive(product, variant, 1750L);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> inv.getArgument(0));

        List<OrderDetail> placed = service.place(consumer);

        // the placement-instant re-pricing: the frozen line carries the
        // variant's CURRENT price, not the add-instant copy
        assertThat(placed).hasSize(1);
        assertThat(line.getUnitAmountMinor()).isEqualTo(1750L);
        ArgumentCaptor<OrderItem> frozen = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).save(frozen.capture());
        assertThat(frozen.getValue().getVariantId()).isEqualTo(variant);
        assertThat(frozen.getValue().getUnitAmountMinor()).isEqualTo(1750L);
        assertThat(placed.get(0).order().getTotalAmountMinor()).isEqualTo(3500L);
        // the reservation spans the VARIANT shelf only (no product-level
        // line exists in this cart) — the all-or-nothing law crossed over
        verify(productStockPort).reserveVariants(java.util.Map.of(variant, 2));
        verify(productStockPort, never()).reserve(any());
    }

    // ------------------------------------------------------------------
    // The ADMIN command surface (Saleor P4): bulk deduplication, per-row
    // error reporting, stable lock ordering, and the returned OrderView.
    // ------------------------------------------------------------------

    @Test
    void bulkCancelDeduplicatesRepeatedIdsAndCancelsOnce() {
        Order order = placedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderBulkCancelResult result = service.bulkCancelAsAdmin(
                List.of(order.getId(), order.getId()));

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).kind())
                .isEqualTo(OrderBulkCancelResult.FailureKind.DUPLICATE);
        assertThat(result.failures().get(0).orderId()).isEqualTo(order.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
        verify(eventPublisher).publishEvent(new OrderCancelledEvent(
                order.getId(), consumer, "Cancelled through the order-management bulk API"));
    }

    @Test
    void bulkCancelReportsMissingAndDeliveryConflictsWithoutBlockingTheLegalRow() {
        Order legal = placedOrder();
        Order linked = placedOrder();
        linked.attachDeliveryRequest(UUID.randomUUID());
        UUID missing = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(legal.getId())).thenReturn(Optional.of(legal));
        when(orderRepository.findByIdForUpdate(linked.getId())).thenReturn(Optional.of(linked));
        when(orderRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderBulkCancelResult result = service.bulkCancelAsAdmin(
                List.of(missing, linked.getId(), legal.getId()));

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.failures())
                .extracting(OrderBulkCancelResult.Failure::kind)
                .containsExactlyInAnyOrder(
                        OrderBulkCancelResult.FailureKind.NOT_FOUND,
                        OrderBulkCancelResult.FailureKind.CONFLICT);
        assertThat(legal.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(linked.getStatus()).isEqualTo(OrderStatus.PLACED);
        verify(orderRepository).save(legal);
        verify(eventPublisher).publishEvent(new OrderCancelledEvent(
                legal.getId(), consumer, "Cancelled through the order-management bulk API"));
    }

    @Test
    void bulkCancelAcquiresRowLocksInStableIdOrderEvenForReversedInput() {
        Order first = placedOrder();
        Order second = placedOrder();
        Order low = first.getId().compareTo(second.getId()) < 0 ? first : second;
        Order high = low == first ? second : first;
        when(orderRepository.findByIdForUpdate(low.getId())).thenReturn(Optional.of(low));
        when(orderRepository.findByIdForUpdate(high.getId())).thenReturn(Optional.of(high));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderBulkCancelResult result = service.bulkCancelAsAdmin(
                List.of(high.getId(), low.getId()));

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.failures()).isEmpty();
        var locks = inOrder(orderRepository);
        locks.verify(orderRepository).findByIdForUpdate(low.getId());
        locks.verify(orderRepository).findByIdForUpdate(high.getId());
    }

    @Test
    void adminConfirmAndCancelReturnTheTransitionedOrderView() {
        Order order = placedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderView confirmed = service.confirmAsAdmin(order.getId());
        OrderView cancelled = service.cancelAsAdmin(order.getId());

        assertThat(confirmed.id()).isEqualTo(order.getId());
        assertThat(confirmed.placedAt()).isEqualTo(order.getPlacedAt());
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(cancelled.id()).isEqualTo(order.getId());
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED.name());
    }

    private Order placedOrder() {
        // Order.placed generates its own id (UUID.randomUUID()) — no
        // reflection needed; the helper exists for the one read that
        // matters: a fresh PLACED machine state.
        return Order.placed(consumer, 5000L, "SAR", null);
    }

    private Order soldOrder() {
        // The merchant surface's fixture: the same fresh PLACED machine
        // state, placed under THIS test's seller (the A1 space — the
        // users.id the seller-scope gate compares the caller against).
        return Order.placed(consumer, 5000L, "SAR", seller);
    }
}
