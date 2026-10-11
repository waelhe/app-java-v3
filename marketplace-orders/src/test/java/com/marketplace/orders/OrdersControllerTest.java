package com.marketplace.orders;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
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
 * A-11 controller unit tests — the domain-module house pattern (direct
 * invocation with the service mocked, {@code BookingControllerTest}
 * precedent): the surface's contract is the controller's own mapping —
 * status codes, the current-user resolution, the response records' shapes.
 * The bean-validation gate and the wire contract are measured by the
 * journey IT on the real chain.
 */
@ExtendWith(MockitoExtension.class)
class OrdersControllerTest {

    @Mock
    private OrdersService ordersService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private OrdersController controller;

    @InjectMocks
    private CartController cartController;

    private final UUID caller = UUID.randomUUID();

    @BeforeEach
    void resolveCaller() {
        // lenient: the /me-family calls resolve the caller; the id-addressed
        // reads delegate the check into the service — the stub is only
        // exercised by the family members that need it.
        org.mockito.Mockito.lenient()
                .when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
    }

    @Test
    void placeAnswersCreatedWithTheDerivedTotal() {
        Order order = Order.placed(caller, 17400L, "SAR", null);
        when(ordersService.place(caller)).thenReturn(new OrderDetail(order, List.of(
                OrderItem.snapshotOf(order.getId(),
                        CartItem.of(Cart.activeFor(caller).getId(), UUID.randomUUID(), 2, 8700L, "SAR")))));

        ResponseEntity<OrderResponses.OrderResponse> result = controller.place(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponses.OrderResponse body = result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(body.totalAmountMinor()).isEqualTo(17400L);
        assertThat(body.items()).hasSize(1);
        assertThat(body.items().get(0).lineTotalMinor()).isEqualTo(17400L);
        assertThat(body.confirmedAt()).isNull();
    }

    @Test
    void getOwnOrderAnswersTheSnapshot() {
        Order order = Order.placed(caller, 5000L, "SAR", null);
        when(ordersService.getOrderDetailForUser(order.getId(), authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        ResponseEntity<OrderResponses.OrderResponse> result =
                controller.getById(order.getId(), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().id()).isEqualTo(order.getId());
    }

    @Test
    void listMineAnswersThePage() {
        when(ordersService.listByConsumer(caller, PageRequest.of(0, 20), authentication))
                .thenReturn(new PageImpl<>(List.<OrderDetail>of()));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<OrderResponses.OrderResponse>> result =
                controller.listMine(PageRequest.of(0, 20), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
    }

    @Test
    void cancelAnswersTheCancelledState() {
        Order order = Order.placed(caller, 5000L, "SAR", null);
        order.cancel("changed mind", java.time.Instant.now());
        when(ordersService.cancelForUser(order.getId(), "changed mind", authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        ResponseEntity<OrderResponses.OrderResponse> result =
                controller.cancel(order.getId(), new OrdersController.CancelOrderRequest("changed mind"), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.getBody().cancelReason()).isEqualTo("changed mind");
        assertThat(result.getBody().cancelledAt()).isNotNull();
        verify(ordersService).cancelForUser(order.getId(), "changed mind", authentication);
    }

    @Test
    void theEmptyCartSurfacesTheServices409Untouched() {
        when(ordersService.place(caller))
                .thenThrow(new ConflictException("Cannot place an order from an empty cart"));

        assertThatThrownBy(() -> controller.place(authentication))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void getCartAnswersTheLinesWithTheirTotals() {
        Cart cart = Cart.activeFor(caller);
        CartItem line = CartItem.of(cart.getId(), UUID.randomUUID(), 2, 1500L, "SAR");
        when(ordersService.getActiveCartItems(caller)).thenReturn(List.of(line));

        ResponseEntity<List<OrderResponses.CartItemResponse>> result =
                cartController.getCart(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        OrderResponses.CartItemResponse body = result.getBody().get(0);
        assertThat(body.quantity()).isEqualTo(2);
        assertThat(body.lineTotalMinor()).isEqualTo(3000L);
        assertThat(body.currency()).isEqualTo("SAR");
    }

    @Test
    void addCartItemAnswersCreatedAndDelegatesTheUnion() {
        UUID product = UUID.randomUUID();
        Cart cart = Cart.activeFor(caller);
        CartItem line = CartItem.of(cart.getId(), product, 2, 1500L, "SAR");
        when(ordersService.addCartItem(caller, product, 2)).thenReturn(line);

        ResponseEntity<OrderResponses.CartItemResponse> result = cartController.addItem(
                new CartController.AddCartItemRequest(product, 2, 1500L, "SAR"), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().productId()).isEqualTo(product);
        verify(ordersService).addCartItem(caller, product, 2);
    }
}
