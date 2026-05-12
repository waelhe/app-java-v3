package com.marketplace.pricing;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = ApiConstants.PRICING, version = "1.0")
public class PricingController {
    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @PostMapping("/calculate")
    public ResponseEntity<PricingService.PriceBreakdown> calculate(@Valid @RequestBody CalculatePriceRequest request) {
        return ResponseEntity.ok(pricingService.calculatePrice(request.basePriceCents(), request.category()));
    }

    public record CalculatePriceRequest(@NotNull @Min(0) Long basePriceCents, String category) {
    }
}
