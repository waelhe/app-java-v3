package com.marketplace.app.graphql;

import com.marketplace.orders.spi.CheckoutSpi;
import com.marketplace.orders.spi.OrderCommandSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CheckoutDataFetcherTest {
    private CheckoutSpi checkoutSpi;
    private CheckoutGraphQlMapper mapper;
    private CheckoutDataFetcher fetcher;

    @BeforeEach void setUp() {
        checkoutSpi = mock(CheckoutSpi.class);
        mapper = mock(CheckoutGraphQlMapper.class);
        OrderCommandSpi orderCommandSpi = mock(OrderCommandSpi.class);
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

    @Test void malformedNonEmptyIdRemainsAClassifiedValidationFailure() {
        assertThatThrownBy(() -> fetcher.checkout("not-a-checkout-global-id"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(checkoutSpi, mapper);
    }
}
