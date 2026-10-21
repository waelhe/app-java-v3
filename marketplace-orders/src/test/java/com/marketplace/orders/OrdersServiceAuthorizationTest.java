package com.marketplace.orders;

import com.marketplace.orders.spi.OrderCommandSpi;
import com.marketplace.shared.api.CheckoutDeliveryPort;
import com.marketplace.shared.api.CommercialPricingPort;
import com.marketplace.shared.api.OrderPaymentPort;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ProductStockPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies the actual Spring method-security proxy exposed through the orders
 * SPI. A non-ADMIN caller is rejected before any repository, stock or payment
 * collaborator is touched.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrdersServiceAuthorizationTest.TestConfig.class)
class OrdersServiceAuthorizationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(OrdersService.class)
    static class TestConfig {
    }

    @MockitoBean
    private CartRepository cartRepository;
    @MockitoBean
    private CartItemRepository cartItemRepository;
    @MockitoBean
    private OrderRepository orderRepository;
    @MockitoBean
    private OrderItemRepository orderItemRepository;
    @MockitoBean
    private CurrentUserProvider currentUserProvider;
    @MockitoBean
    private ProductPricingPort productPricingPort;
    @MockitoBean
    private ProductStockPort productStockPort;
    @MockitoBean
    private OrderPaymentPort orderPaymentPort;
    @MockitoBean
    private CommercialPricingPort commercialPricingPort;
    @MockitoBean
    private OrderPriceComponentRepository orderPriceComponentRepository;
    @MockitoBean
    private CheckoutDeliveryPort checkoutDeliveryPort;

    @Autowired
    private OrderCommandSpi orderCommandSpi;

    @Test
    @WithMockUser(roles = "USER")
    void cancelAsAdminRejectsNonAdminBeforeAnyDomainSideEffect() {
        assertThatThrownBy(() -> orderCommandSpi.cancelAsAdmin(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orderCommandSpi.confirmAsAdmin(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orderCommandSpi.bulkCancelAsAdmin(List.of(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orderCommandSpi.bulkCancelAsAdmin(List.of()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(
                cartRepository, cartItemRepository, orderRepository, orderItemRepository,
                currentUserProvider, productPricingPort, productStockPort, orderPaymentPort,
                commercialPricingPort, orderPriceComponentRepository, checkoutDeliveryPort);
    }
}
