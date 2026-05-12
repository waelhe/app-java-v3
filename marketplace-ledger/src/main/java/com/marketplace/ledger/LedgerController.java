package com.marketplace.ledger;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
public class LedgerController {
    private final LedgerService ledgerService;
    public LedgerController(LedgerService ledgerService) { this.ledgerService = ledgerService; }

    @GetMapping("/ledger")
    public ResponseEntity<PagedResponse<LedgerEntry>> list(Pageable pageable) { return ResponseEntity.ok(PagedResponse.of(ledgerService.list(pageable))); }

    @GetMapping("/providers/{providerId}/balance")
    public ResponseEntity<ProviderBalance> balance(@PathVariable UUID providerId) { return ResponseEntity.ok(ledgerService.balance(providerId)); }

    @PostMapping("/providers/{providerId}/payouts")
    public ResponseEntity<Payout> payout(@PathVariable UUID providerId, @Valid @RequestBody PayoutRequest request) {
        return ResponseEntity.ok(ledgerService.createPayout(providerId, request.amountCents()));
    }
    public record PayoutRequest(@NotNull @Min(1) Long amountCents) {}
}
