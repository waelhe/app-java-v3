package com.marketplace.pricing;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
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
    private final PricingRuleMapper pricingRuleMapper;

    public PricingRuleController(PricingService pricingService, PricingRuleMapper pricingRuleMapper) {
        this.pricingService = pricingService;
        this.pricingRuleMapper = pricingRuleMapper;
    }

    @GetMapping
    @Operation(summary = "List pricing rules",
            description = "Every pricing rule with its tax rate, discount percentage and active "
                    + "flag — the administrative catalogue of the effective-price engine's "
                    + "rule inputs.")
    public ResponseEntity<List<PricingRuleResponse>> listRules() {
        List<PricingRuleResponse> rules = pricingService.listRules().stream()
                .map(pricingRuleMapper::toResponse)
                .toList();
        return ResponseEntity.ok(rules);
    }

    @PostMapping
    @Operation(summary = "Create a pricing rule",
            description = "A new named rule (taxRate and discountPct as 0..1 fractions, "
                    + "optionally scoped to one listing category). Rules are born inactive — "
                    + "activate is the separate switch. Answers 201.")
    public ResponseEntity<PricingRuleResponse> createRule(@Valid @RequestBody CreateRuleRequest request) {
        PricingRule rule = pricingService.createRule(
                request.name(),
                request.category(),
                request.taxRate(),
                request.discountPct()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(pricingRuleMapper.toResponse(rule));
    }

    @PutMapping("/{id}/activate")
    @Operation(summary = "Activate a pricing rule",
            description = "Turns the rule on for the effective-price engine's rule resolution.")
    public ResponseEntity<PricingRuleResponse> activateRule(@PathVariable UUID id) {
        return ResponseEntity.ok(pricingRuleMapper.toResponse(pricingService.activate(id)));
    }

    @PutMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a pricing rule",
            description = "Turns the rule off without deleting it — the reversible exit that "
                    + "keeps the rule's history.")
    public ResponseEntity<PricingRuleResponse> deactivateRule(@PathVariable UUID id) {
        return ResponseEntity.ok(pricingRuleMapper.toResponse(pricingService.deactivate(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a pricing rule",
            description = "Removes the rule outright — the irreversible exit (deactivate is "
                    + "the reversible one). Answers 204.")
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
