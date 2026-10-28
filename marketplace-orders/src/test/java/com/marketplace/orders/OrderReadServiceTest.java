package com.marketplace.orders;

import com.marketplace.orders.spi.OrderPage;
import com.marketplace.orders.spi.OrderSort;
import com.marketplace.orders.spi.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4 (the canonical Saleor plan §5-P4 — packet campaign-b5-b, the
 * {@code order}/{@code orders} read roots): the owning use case's unit
 * proof — the JUnit 5 + Mockito style the module's own
 * {@code ProductReadServiceTest}/{@code OrderReturnsServiceTest}
 * established (no Spring context: the {@code @PreAuthorize} method
 * security is an integration concern proven by the app module's
 * real-stack tests against the REAL security chain; the business
 * decisions the boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of {@link OrderReadService}
 * reachable through its typed seam — the three executed sorts (both
 * directions, the derived repository method each rides), the STATUS
 * sort's keyset rebinding (the decoded cursor's text-form status key
 * rebuilt as the entity's own enum — and the unparseable value's
 * classified rejection), the lookup's safe-absence legs (null key,
 * unknown key), the required-sort/positive-page guards, and the
 * bounded projection itself.</p>
 */
class OrderReadServiceTest {

    private OrderRepository orderRepository;
    private OrderReadService service;
    private Authentication caller;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        service = new OrderReadService(orderRepository);
        caller = mock(Authentication.class);
    }

    private static Order placedOrder() {
        return Order.placed(UUID.randomUUID(), 2500L, "SAR", UUID.randomUUID());
    }

    // ------------------------------------------------------------------
    // findOrderById — the safe-absence legs
    // ------------------------------------------------------------------

    @Test
    void nullKeyAnswersEmpty() {
        assertThat(service.findOrderById(null, caller)).isEmpty();
        verify(orderRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void unknownKeyAnswersTheSafeAbsence() {
        UUID unknown = UUID.randomUUID();
        when(orderRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findOrderById(unknown, caller)).isEmpty();
    }

    @Test
    void theBoundedProjectionCarriesTheOwningFacts() {
        Order order = placedOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        Optional<OrderView> view = service.findOrderById(order.getId(), caller);
        assertThat(view).isPresent();
        assertThat(view.get().id()).isEqualTo(order.getId());
        assertThat(view.get().status()).isEqualTo("PLACED");
        assertThat(view.get().placedAt()).isEqualTo(order.getPlacedAt());
    }

    // ------------------------------------------------------------------
    // scrollOrders — the guards
    // ------------------------------------------------------------------

    @Test
    void missingSortIsRejected() {
        assertThatThrownBy(() -> service.scrollOrders(caller, null,
                ScrollPosition.keyset(), Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositivePageSizeIsRejected() {
        assertThatThrownBy(() -> service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.CREATED_AT, true),
                ScrollPosition.keyset(), Limit.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // scrollOrders — the three executed sorts
    // ------------------------------------------------------------------

    @Test
    void createdAtSortRidesThePlacementInstantScroll() {
        Order order = placedOrder();
        Window<Order> frameworkWindow = Window.from(List.of(order),
                i -> ScrollPosition.forward(Map.of(
                        "placedAt", order.getPlacedAt(), "id", order.getId())), true);
        when(orderRepository.findByOrderByPlacedAtDescIdDesc(any(), any()))
                .thenReturn(frameworkWindow);
        when(orderRepository.count()).thenReturn(17L);

        OrderPage page = service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.CREATED_AT, false),
                ScrollPosition.keyset(), Limit.of(10));

        verify(orderRepository).findByOrderByPlacedAtDescIdDesc(any(), any());
        assertThat(page.total()).isEqualTo(17L);
        assertThat(page.window().hasNext()).isTrue();
        assertThat(page.window().getContent()).hasSize(1);
        assertThat(page.window().getContent().get(0).status()).isEqualTo("PLACED");
    }

    @Test
    void lastModifiedSortRidesTheAuditInstantScroll() {
        Order order = placedOrder();
        Window<Order> frameworkWindow = Window.from(List.of(order),
                i -> ScrollPosition.forward(Map.of("id", order.getId())), false);
        when(orderRepository.findByOrderByUpdatedAtAscIdAsc(any(), any()))
                .thenReturn(frameworkWindow);
        when(orderRepository.count()).thenReturn(1L);

        OrderPage page = service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.LAST_MODIFIED_AT, true),
                ScrollPosition.keyset(), Limit.of(10));

        verify(orderRepository).findByOrderByUpdatedAtAscIdAsc(any(), any());
        assertThat(page.total()).isEqualTo(1L);
    }

    @Test
    void statusSortRidesTheLifecycleTextScroll() {
        Order order = placedOrder();
        Window<Order> frameworkWindow = Window.from(List.of(order),
                i -> ScrollPosition.forward(Map.of("status", OrderStatus.PLACED,
                        "id", order.getId())), false);
        when(orderRepository.findByOrderByStatusAscIdAsc(any(), any()))
                .thenReturn(frameworkWindow);
        when(orderRepository.count()).thenReturn(1L);

        OrderPage page = service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.STATUS, true),
                ScrollPosition.keyset(), Limit.of(10));

        verify(orderRepository).findByOrderByStatusAscIdAsc(any(), any());
        assertThat(page.window().getContent()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // the STATUS sort's keyset rebinding
    // ------------------------------------------------------------------

    @Test
    void statusCursorKeysAreReboundAsTheEntityVocabulary() {
        Order order = placedOrder();
        // The boundary's gate hands the status key in its decoded text
        // form (the JSON cursor's own shape) — the use case rebinds it
        // as the entity's own enum before the framework scroll.
        KeysetScrollPosition decoded = (KeysetScrollPosition) ScrollPosition.forward(Map.of(
                "status", "PLACED", "id", order.getId()));
        Window<Order> frameworkWindow = Window.from(List.of(order),
                i -> ScrollPosition.forward(Map.of("status", OrderStatus.PLACED,
                        "id", order.getId())), false);
        org.mockito.ArgumentCaptor<KeysetScrollPosition> positionCaptor =
                org.mockito.ArgumentCaptor.forClass(KeysetScrollPosition.class);
        when(orderRepository.findByOrderByStatusDescIdDesc(positionCaptor.capture(), any()))
                .thenReturn(frameworkWindow);
        when(orderRepository.count()).thenReturn(1L);

        service.scrollOrders(caller, OrderSort.of(OrderSort.Field.STATUS, false),
                decoded, Limit.of(10));

        KeysetScrollPosition passed = positionCaptor.getValue();
        assertThat(passed.getKeys().get("status")).isEqualTo(OrderStatus.PLACED);
        assertThat(passed.getKeys().get("id")).isEqualTo(order.getId());
    }

    @Test
    void unparseableStatusCursorAnswersTheClassifiedRejection() {
        KeysetScrollPosition decoded = (KeysetScrollPosition) ScrollPosition.forward(Map.of(
                "status", "NOT_A_STATUS", "id", UUID.randomUUID()));

        assertThatThrownBy(() -> service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.STATUS, true),
                decoded, Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset cursor emitted by orders");
    }

    @Test
    void initialStatusPositionPassesThroughUnrebound() {
        Order order = placedOrder();
        Window<Order> frameworkWindow = Window.from(List.of(order),
                i -> ScrollPosition.forward(Map.of("status", OrderStatus.PLACED,
                        "id", order.getId())), false);
        when(orderRepository.findByOrderByStatusAscIdAsc(any(), any()))
                .thenReturn(frameworkWindow);
        when(orderRepository.count()).thenReturn(1L);

        service.scrollOrders(caller, OrderSort.of(OrderSort.Field.STATUS, true),
                ScrollPosition.keyset(), Limit.of(10));

        verify(orderRepository).findByOrderByStatusAscIdAsc(any(), any());
    }
}
