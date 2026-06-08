package com.marketplace.pricing;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.PRICING + "/rules")
@PreAuthorize("hasRole('ADMIN')")
public class PricingRuleController {

    private final PricingService pricingService;

    public PricingRuleController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping
    public ResponseEntity<List<PricingRule>> listRules() {
        return ResponseEntity.ok(pricingService.listRules());
    }

    @PostMapping
    public ResponseEntity<PricingRule> createRule(@Valid @RequestBody CreateRuleRequest request) {
        PricingRule rule = pricingService.createRule(
                request.name(),
                request.category(),
                request.taxRate(),
                request.discountPct()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<PricingRule> activateRule(@PathVariable UUID id) {
        return ResponseEntity.ok(pricingService.activate(id));
    }

    @PutMapping("/{id}/deactivate")
    public ResponseEntity<PricingRule> deactivateRule(@PathVariable UUID id) {
        return ResponseEntity.ok(pricingService.deactivate(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        pricingService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRuleRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String category,
            @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
            @DecimalMin("0") @DecimalMax("1") BigDecimal discountPct
    ) {}
}
