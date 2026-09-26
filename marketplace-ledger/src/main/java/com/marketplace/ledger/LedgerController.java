package com.marketplace.ledger;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(summary = "Credit a provider's ledger balance",
            description = "L24 money path — credits the provider's balance for a payment "
                    + "intent: exactly one PAYMENT_CREDIT entry per intent (idempotent by "
                    + "source id; a replay answers the current balance without a second "
                    + "entry). Amounts are non-negative cents; zero is a no-op.")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderBalance> creditProvider(@PathVariable UUID providerId,
                                                          @RequestParam UUID paymentIntentId,
                                                          @RequestParam long amountCents) {
        return ResponseEntity.ok(ledgerService.creditFromPayment(providerId, paymentIntentId, amountCents));
    }

    @GetMapping("/admin/ledger/providers/{providerId}/balance")
    @Operation(summary = "Read a provider's ledger balance",
            description = "The provider's current ledger balance in cents — the balance the "
                    + "credit/debit money paths maintain.")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderBalance> getProviderBalance(@PathVariable UUID providerId) {
        return ResponseEntity.ok(ledgerService.getBalance(providerId));
    }
}
