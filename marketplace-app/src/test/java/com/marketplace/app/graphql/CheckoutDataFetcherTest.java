package com.marketplace.app.graphql;

import com.marketplace.orders.spi.CheckoutSpi;
import com.marketplace.orders.spi.OrderCommandSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CheckoutDataFetcherTest {
    private CheckoutSpi checkoutSpi;
    private CheckoutGraphQlMapper mapper;
    private OrderCommandSpi orderCommandSpi;
    private CheckoutDataFetcher fetcher;

    @BeforeEach void setUp() {
        checkoutSpi = mock(CheckoutSpi.class);
        mapper = mock(CheckoutGraphQlMapper.class);
        orderCommandSpi = mock(OrderCommandSpi.class);
        fetcher = new CheckoutDataFetcher(checkoutSpi, orderCommandSpi, mapper, new SaleorGlobalIdCodec());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("checkout-owner", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void absentOrBlankOptionalIdAnswersNullWithoutCallingTheOwner() {
        assertThat(fetcher.checkout(null)).isNull();
        assertThat(fetcher.checkout("   ")).isNull();
        verifyNoInteractions(checkoutSpi, mapper);
    }

    @Test void checkoutDeleteAuthorizationDenialPrecedesGlobalIdDecoding() {
        doThrow(new AccessDeniedException("ADMIN required")).when(orderCommandSpi).requireAdmin();

        assertThatThrownBy(() -> fetcher.checkoutDelete("not-a-checkout-global-id"))
                .isInstanceOf(AccessDeniedException.class);

        verify(orderCommandSpi).requireAdmin();
        verifyNoInteractions(checkoutSpi);
    }

    @Test void malformedNonEmptyIdRemainsAClassifiedValidationFailure() {
        assertThatThrownBy(() -> fetcher.checkout("not-a-checkout-global-id"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(checkoutSpi, mapper);
    }
}
