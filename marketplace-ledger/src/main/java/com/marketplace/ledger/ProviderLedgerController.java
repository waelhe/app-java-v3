package com.marketplace.ledger;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Provider self-service ledger reads (L20 — feature-expansion roadmap §5).
 *
 * <p>Two read-only endpoints under {@code /api/v1/providers/me/ledger}: the
 * current balance and a paginated statement of movements. The "me" provider is
 * resolved from the authenticated user via {@link ProviderLookupPort} — the
 * client never supplies a provider id — and every service call is guarded by
 * the unit's ownership convention ({@code @authHelper.ownsProvider} on
 * {@code LedgerService#getBalanceForOwner}/{@code getStatementForOwner}), so
 * the resolution and the authorization are two independent checks
 * (defense-in-depth, same seam as availability).
 *
 * <p>Users without a provider profile get 404 (ResourceNotFound), the house
 * answer for "no such resource for this caller". The ADMIN surface keeps its
 * existing {@code /admin/ledger/**} endpoints — nothing here changes them.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderLedgerController {

    private final LedgerService ledgerService;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;

    public ProviderLedgerController(LedgerService ledgerService,
                                    CurrentUserProvider currentUserProvider,
                                    ProviderLookupPort providerLookupPort) {
        this.ledgerService = ledgerService;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
    }

    @GetMapping("/providers/me/ledger/balance")
    @Operation(summary = "Get my ledger balance (L20)",
            description = "The calling provider's current ledger balance in minor units — the "
                    + "amount credited from completed payments.")
    public ResponseEntity<ProviderBalance> getMyBalance(Authentication authentication) {
        return ResponseEntity.ok(ledgerService.getBalanceForOwner(requireOwnProviderId(authentication)));
    }

    @GetMapping("/providers/me/ledger/statement")
    @Operation(summary = "Get my ledger statement (L20)",
            description = "Paginated ledger movements for the calling provider.")
    public ResponseEntity<PagedResponse<LedgerEntryResponse>> getMyStatement(Authentication authentication,
                                                                             Pageable pageable) {
        UUID providerId = requireOwnProviderId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                ledgerService.getStatementForOwner(providerId, pageable).map(LedgerEntryResponse::from)));
    }

    private UUID requireOwnProviderId(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return providerLookupPort.findByUserId(userId)
                .map(ProviderSummary::id)
                .orElseThrow(() -> new ResourceNotFoundException("No provider profile for the current user"));
    }
}
