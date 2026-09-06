package com.marketplace.pricing;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.IsoCurrencyCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Currency conversion endpoint (roadmap B4): a read-only display utility
 * for money denominated in any ISO 4217 code the deployment quotes. The
 * exchange channel is a dormant good — when no rates are bound this
 * endpoint answers the documented 503 SU-001 instead of half-working
 * (PricingService.convert owns that contract).
 */
@RestController
@RequestMapping(value = ApiConstants.PRICING + "/convert")
public class CurrencyExchangeController {

    private final PricingService pricingService;

    public CurrencyExchangeController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping
    public ResponseEntity<ConversionResponse> convert(
            @RequestParam @NotNull @Min(0) Long amountCents,
            @RequestParam @NotBlank @IsoCurrencyCode String from,
            @RequestParam @NotBlank @IsoCurrencyCode String to) {
        CurrencyExchangePort.ExchangeQuote quote = pricingService.convert(amountCents, from, to);
        return ResponseEntity.ok(new ConversionResponse(
                quote.sourceMinorUnits(),
                quote.sourceCurrency(),
                quote.targetMinorUnits(),
                quote.targetCurrency(),
                quote.rate(),
                quote.rateSource()
        ));
    }

    public record ConversionResponse(
            Long amountCents,
            String from,
            Long convertedCents,
            String to,
            java.math.BigDecimal rate,
            String source
    ) {
    }
}
