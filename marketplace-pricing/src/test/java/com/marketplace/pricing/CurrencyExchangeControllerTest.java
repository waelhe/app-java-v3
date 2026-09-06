package com.marketplace.pricing;

import com.marketplace.shared.api.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrencyExchangeControllerTest {

    private final PricingService pricingService = mock(PricingService.class);
    private final CurrencyExchangeController controller = new CurrencyExchangeController(pricingService);

    @Test
    void convert_returnsTheQuote() {
        when(pricingService.convert(10_000L, "SAR", "USD")).thenReturn(
                new CurrencyExchangePort.ExchangeQuote(10_000L, "SAR", 2_667L, "USD",
                        new BigDecimal("0.266666666667"), "static-config"));

        var result = controller.convert(10_000L, "SAR", "USD");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(10_000L, result.getBody().amountCents());
        assertEquals("SAR", result.getBody().from());
        assertEquals(2_667L, result.getBody().convertedCents());
        assertEquals("USD", result.getBody().to());
        assertEquals("static-config", result.getBody().source());
    }

    @Test
    void convert_dormantChannel_propagatesThe503Contract() {
        when(pricingService.convert(anyLong(), anyString(), anyString()))
                .thenThrow(new ServiceUnavailableException("Currency exchange channel is not configured."));

        assertThrows(ServiceUnavailableException.class,
                () -> controller.convert(10_000L, "SAR", "USD"));
    }
}
