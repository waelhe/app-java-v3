package com.marketplace.ledger;

import com.marketplace.shared.api.ApiConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/admin/ledger/providers/{providerId}/credit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderBalance> creditProvider(@PathVariable UUID providerId,
                                                          @RequestParam UUID paymentIntentId,
                                                          @RequestParam long amountCents) {
        return ResponseEntity.ok(ledgerService.creditFromPayment(providerId, paymentIntentId, amountCents));
    }

    @GetMapping("/admin/ledger/providers/{providerId}/balance")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderBalance> getProviderBalance(@PathVariable UUID providerId) {
        return ResponseEntity.ok(ledgerService.getBalance(providerId));
    }
}
