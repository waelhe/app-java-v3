package com.marketplace.orders.spi;

import com.marketplace.orders.FulfillmentMethod;
import com.marketplace.orders.Order;
import com.marketplace.orders.OrderRepository;
import com.marketplace.shared.api.OrderSettlementFacts;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the ADR-0013
 * D4 execution): the widened settlement-facts seam's contract proofs —
 * the frozen fee split the ledger's ORDER-origin money paths follow.
 */
@ExtendWith(MockitoExtension.class)
class OrderSellerAdapterTest {

    private static final UUID CONSUMER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();

    @Mock
    private OrderRepository orderRepository;

    @Test
    void platformDeliveryOrderCarriesItsFrozenFeeAndFlag() {
        // The V224 placement shape: the fee sits beside the total (already
        // inside it) — the facts carry it with the platform-delivery flag.
        Order order = Order.placedWithFulfillment(CONSUMER, "SAR", SELLER,
                12000L, 0L, 1400L, true, FulfillmentMethod.PLATFORM_DELIVERY,
                "Riyadh", 24.7, 46.7, 4000L);
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderSettlementFacts facts = new OrderSellerAdapter(orderRepository).settlementFactsOf(orderId);

        assertThat(facts.sellerId()).isEqualTo(SELLER);
        assertThat(facts.shippingAmountMinor()).isEqualTo(4000L);
        assertThat(facts.platformDelivery()).isTrue();
    }

    @Test
    void pickupOrderCarriesTheZeroFeeShape() {
        // The V224 placement law: PICKUP records the method with no
        // destination and no fee — the facts carry the zero-fee shape.
        Order order = Order.placedWithFulfillment(CONSUMER, "SAR", SELLER,
                12000L, 0L, 1400L, true, FulfillmentMethod.PICKUP,
                null, null, null, 0L);
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderSettlementFacts facts = new OrderSellerAdapter(orderRepository).settlementFactsOf(orderId);

        assertThat(facts.sellerId()).isEqualTo(SELLER);
        assertThat(facts.shippingAmountMinor()).isZero();
        assertThat(facts.platformDelivery()).isFalse();
    }

    @Test
    void theHonestNegativesAnswerSellerOfOwn404Shapes() {
        OrderSellerAdapter adapter = new OrderSellerAdapter(orderRepository);
        UUID orderId = UUID.randomUUID();
        // The absent row.
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.settlementFactsOf(orderId))
                .isInstanceOf(ResourceNotFoundException.class);
        // The legacy seller-less row (the pre-invariant shape — the same
        // honest 404 sellerOf itself answers).
        Order legacy = Order.placedWithBreakdown(CONSUMER, "SAR", null, 12000L, 0L, 1400L, true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(legacy));
        assertThatThrownBy(() -> adapter.settlementFactsOf(orderId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
