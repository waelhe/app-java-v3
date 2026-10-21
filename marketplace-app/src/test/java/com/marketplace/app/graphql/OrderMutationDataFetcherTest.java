package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Order;
import com.marketplace.orders.OrderStatus;
import com.marketplace.orders.OrdersService;
import com.marketplace.orders.spi.OrderView;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderMutationDataFetcherTest {

    @Mock
    private OrdersService ordersService;
    @Mock
    private OrderGraphQlMapper mapper;

    private final SaleorGlobalIdCodec codec = new SaleorGlobalIdCodec();
    private OrderMutationDataFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new OrderMutationDataFetcher(ordersService, mapper, codec);
    }

    @Test
    void cancellationDelegatesToOwnerAndReturnsReadBackWithEmptyErrors() {
        UUID id = UUID.randomUUID();
        Instant placedAt = Instant.parse("2026-10-20T12:00:00Z");
        com.marketplace.orders.Order cancelled = org.mockito.Mockito.mock(
                com.marketplace.orders.Order.class);
        Order graphQlOrder = org.mockito.Mockito.mock(Order.class);
        when(cancelled.getId()).thenReturn(id);
        when(cancelled.getPlacedAt()).thenReturn(placedAt);
        when(cancelled.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(ordersService.cancelAsAdmin(id)).thenReturn(cancelled);
        when(mapper.toGraphQlOrder(any(OrderView.class))).thenReturn(graphQlOrder);

        Map<String, Object> result = fetcher.orderCancel(codec.encode("Order", id));

        assertThat(result).containsEntry("order", graphQlOrder);
        assertThat((List<?>) result.get("orderErrors")).isEmpty();
        ArgumentCaptor<OrderView> view = ArgumentCaptor.forClass(OrderView.class);
        verify(mapper).toGraphQlOrder(view.capture());
        assertThat(view.getValue()).isEqualTo(new OrderView(id, placedAt, "CANCELLED"));
        verify(ordersService).cancelAsAdmin(id);
    }

    @Test
    void mistypedGlobalIdReturnsValidationPayloadWithoutCallingOwner() {
        UUID id = UUID.randomUUID();

        Map<String, Object> result = fetcher.orderCancel(codec.encode("Product", id));

        assertThat(result.get("order")).isNull();
        List<?> errors = (List<?>) result.get("orderErrors");
        assertThat(errors).hasSize(1);
        assertThat(((Map<?, ?>) errors.get(0)).get("field")).isEqualTo("id");
        assertThat(((Map<?, ?>) errors.get(0)).get("code")).isEqualTo("INVALID");
        verifyNoInteractions(ordersService, mapper);
    }

    @Test
    void illegalCancellationReturnsOrderErrorInsteadOfFalseSuccess() {
        UUID id = UUID.randomUUID();
        when(ordersService.cancelAsAdmin(id))
                .thenThrow(new ConflictException("Order cannot be cancelled in its current state"));

        Map<String, Object> result = fetcher.orderCancel(codec.encode("Order", id));

        assertThat(result.get("order")).isNull();
        List<?> errors = (List<?>) result.get("orderErrors");
        assertThat(errors).hasSize(1);
        assertThat(((Map<?, ?>) errors.get(0)).get("code")).isEqualTo("CANNOT_CANCEL_ORDER");
        verify(ordersService).cancelAsAdmin(id);
    }
}
