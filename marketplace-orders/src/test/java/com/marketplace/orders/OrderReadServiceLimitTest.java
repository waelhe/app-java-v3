package com.marketplace.orders;

import com.marketplace.orders.spi.OrderSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderReadServiceLimitTest {

    private OrderRepository orderRepository;
    private OrderReadService service;
    private Authentication caller;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        service = new OrderReadService(orderRepository);
        caller = mock(Authentication.class);
    }

    @Test
    void rejectsNullLimit() {
        assertThatThrownBy(() -> service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.CREATED_AT, true),
                ScrollPosition.keyset(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void rejectsUnlimitedLimitBeforeCallingMax() {
        Limit limit = Limit.unlimited();
        assertThat(limit.isUnlimited()).isTrue();

        assertThatThrownBy(() -> service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.CREATED_AT, true),
                ScrollPosition.keyset(), limit))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void rejectsZeroLimit() {
        assertThatThrownBy(() -> service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.CREATED_AT, true),
                ScrollPosition.keyset(), Limit.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void rejectsNegativeLimit() {
        assertThatThrownBy(() -> service.scrollOrders(caller,
                OrderSort.of(OrderSort.Field.CREATED_AT, true),
                ScrollPosition.keyset(), Limit.of(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository);
    }
}
