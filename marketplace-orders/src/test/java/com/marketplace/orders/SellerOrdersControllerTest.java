package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The merchant surface's controller unit tests — the domain-module house
 * pattern (direct invocation with the service mocked, the
 * {@code OrdersControllerTest} precedent): the surface's contract is the
 * controller's own mapping — status codes, the delegation shapes, and the
 * response records' post-command read-back. The seller-scope gate, the
 * bean-validation boundary, and the wire contract are measured by the
 * service tests and the journey IT on the real chain.
 */
@ExtendWith(MockitoExtension.class)
class SellerOrdersControllerTest {

    @Mock
    private OrdersService ordersService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private SellerOrdersController controller;

    private final UUID seller = UUID.randomUUID();
    private final UUID consumer = UUID.randomUUID();

    @Test
    void listMineAnswersTheSoldPage() {
        when(ordersService.listSoldByCaller(authentication, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.<OrderDetail>of()));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<OrderResponses.OrderResponse>> result =
                controller.listMine(PageRequest.of(0, 20), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
        verify(ordersService).listSoldByCaller(authentication, PageRequest.of(0, 20));
    }

    @Test
    void getByIdAnswersTheFrozenSnapshot() {
        Order order = Order.placed(consumer, 5000L, "SAR", seller);
        when(ordersService.getOrderDetailForSeller(order.getId(), authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        ResponseEntity<OrderResponses.OrderResponse> result =
                controller.getById(order.getId(), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().id()).isEqualTo(order.getId());
        assertThat(result.getBody().totalAmountMinor()).isEqualTo(5000L);
    }

    @Test
    void confirmAnswersTheMachinesNewStateAsTheReadBack() {
        Order order = Order.placed(consumer, 5000L, "SAR", seller);
        order.confirm(java.time.Instant.now());
        when(ordersService.confirmForSeller(order.getId(), authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        ResponseEntity<OrderResponses.OrderResponse> result =
                controller.confirm(order.getId(), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.getBody().confirmedAt()).isNotNull();
        verify(ordersService).confirmForSeller(order.getId(), authentication);
    }

    @Test
    void fulfillAnswersTheTerminalStateAsTheReadBack() {
        Order order = Order.placed(consumer, 5000L, "SAR", seller);
        order.confirm(java.time.Instant.now());
        order.fulfill(java.time.Instant.now());
        when(ordersService.fulfillForSeller(order.getId(), authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        ResponseEntity<OrderResponses.OrderResponse> result =
                controller.fulfill(order.getId(), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(result.getBody().fulfilledAt()).isNotNull();
        verify(ordersService).fulfillForSeller(order.getId(), authentication);
    }

    @Test
    void cancelAnswersTheCancelledStateWithTheReason() {
        Order order = Order.placed(consumer, 5000L, "SAR", seller);
        order.cancel("out of stock", java.time.Instant.now());
        when(ordersService.cancelForSeller(order.getId(), "out of stock", authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        ResponseEntity<OrderResponses.OrderResponse> result = controller.cancel(
                order.getId(), new SellerOrdersController.CancelOrderRequest("out of stock"), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.getBody().cancelReason()).isEqualTo("out of stock");
        assertThat(result.getBody().cancelledAt()).isNotNull();
        verify(ordersService).cancelForSeller(order.getId(), "out of stock", authentication);
    }

    @Test
    void theIllegalEdgeSurfacesTheServices409Untouched() {
        UUID orderId = UUID.randomUUID();
        when(ordersService.confirmForSeller(orderId, authentication))
                .thenThrow(new ConflictException(
                        "Order is CONFIRMED — confirm is legal from PLACED only"));

        assertThatThrownBy(() -> controller.confirm(orderId, authentication))
                .isInstanceOf(ConflictException.class);
    }
}
