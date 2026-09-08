package com.marketplace.pricing;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.IsoCurrencyCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Operation(summary = "Convert money between currencies",
            description = "Display-only conversion of a minor-units amount between two quoted "
                    + "ISO 4217 currencies using the deployment's exchange rates. Answers 503 "
                    + "SU-001 when no rates are bound (G9).")
    public ResponseEntity<ConversionResponse> convert(
            @Parameter(description = "Amount in minor units of the source currency", example = "35000")
            @RequestParam @NotNull @Min(0) Long amountCents,
            @Parameter(description = "Source ISO 4217 currency code", example = "SAR")
            @RequestParam @NotBlank @IsoCurrencyCode String from,
            @Parameter(description = "Target ISO 4217 currency code", example = "USD")
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

    @Schema(description = "Conversion result with the rate actually applied")
    public record ConversionResponse(
            @Schema(description = "Source amount in minor units", example = "35000")
            Long amountCents,
            @Schema(description = "Source ISO 4217 currency", example = "SAR")
            String from,
            @Schema(description = "Converted amount in minor units", example = "9333")
            Long convertedCents,
            @Schema(description = "Target ISO 4217 currency", example = "USD")
            String to,
            @Schema(description = "Applied exchange rate (target per source unit)", example = "0.26666")
            java.math.BigDecimal rate,
            @Schema(description = "Rate source identifier", example = "static-env")
            String source
    ) {
    }
}
