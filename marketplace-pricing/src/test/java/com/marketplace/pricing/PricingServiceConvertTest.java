package com.marketplace.pricing;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PricingServiceConvertTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<CurrencyExchangePort> none = mock(ObjectProvider.class);

    @Test
    void dormantChannel_answers503WithTheExactBindingRecipe() {
        var service = new PricingService(null, null, none);

        var thrown = assertThatThrownBy(() -> service.convert(10_000L, "SAR", "USD"));

        thrown.isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Currency exchange channel is not configured")
                .hasMessageContaining("marketplace.pricing.currency.exchange.rates");
    }

    @Test
    void legacyConstructor_keepsTheDormantDefault() {
        var service = new PricingService(null, null);

        assertThatThrownBy(() -> service.convert(10_000L, "SAR", "USD"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void boundChannel_delegatesToThePort() {
        var port = new StaticRatesCurrencyExchange(new CurrencyExchangeProperties(
                "SAR", Map.of("USD", new BigDecimal("3.75"))));
        ObjectProvider<CurrencyExchangePort> provider = new ObjectProvider<>() {
            @Override
            public CurrencyExchangePort getObject() {
                return port;
            }

            @Override
            public CurrencyExchangePort getIfAvailable() {
                return port;
            }

            @Override
            public CurrencyExchangePort getIfUnique() {
                return port;
            }
        };
        var service = new PricingService(null, null, provider);

        var quote = service.convert(10_000L, "sar", "usd");

        assertThat(quote.targetCurrency()).isEqualTo("USD");
        assertThat(quote.targetMinorUnits()).isEqualTo(2_667L);
    }

    @Test
    void invalidIsoCode_answers400_not503() {
        var service = new PricingService(null, null, none);

        assertThatThrownBy(() -> service.convert(10_000L, "XYZ", "USD"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("XYZ");
    }

    @Test
    void blankCurrency_answers400() {
        var service = new PricingService(null, null, none);

        assertThatThrownBy(() -> service.convert(10_000L, " ", "USD"))
                .isInstanceOf(BadRequestException.class);
    }
}
