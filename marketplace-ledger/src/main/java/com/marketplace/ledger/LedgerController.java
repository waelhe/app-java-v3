package com.marketplace.ledger;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
                    + "entry). Amounts are non-negative cents; zero is a no-op. R9: the "
                    + "credit carries its ISO 4217 currency and moves the (provider, "
                    + "currency) balance of that currency — blank keeps the house default "
                    + "SAR, exactly the runtime path's own fallback.")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderBalanceResponse> creditProvider(@PathVariable UUID providerId,
                                                          @RequestParam UUID paymentIntentId,
                                                          @RequestParam long amountCents,
                                                          @Parameter(description = "ISO 4217 currency of the credited amount "
                                                                  + "(blank = the house default SAR)")
                                                          @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(ledgerService.creditFromPayment(
                providerId, paymentIntentId, amountCents, currency));
    }

    @GetMapping("/admin/ledger/providers/{providerId}/balance")
    @Operation(summary = "Read a provider's ledger balances",
            description = "The provider's current ledger balances in cents — one row per "
                    + "currency he holds (R9: the balance is keyed (provider, currency); "
                    + "currencies are never summed into one number). A provider with no "
                    + "ledger history answers an empty list.")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ProviderBalanceResponse>> getProviderBalances(@PathVariable UUID providerId) {
        return ResponseEntity.ok(ledgerService.getBalances(providerId));
    }
}
