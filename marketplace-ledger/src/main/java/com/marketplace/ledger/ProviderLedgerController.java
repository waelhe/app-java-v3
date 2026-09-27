package com.marketplace.ledger;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderLookupPort;
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
 * <p><b>The "me" id IS the user id (A1, R10 fix of the frontend battery's
 * LEDGER-403 card BE-04):</b> per the {@code AuthHelper} A1 contract, every
 * cross-module {@code provider_id} column — catalog, booking, reviews, media,
 * availability, <b>ledger</b> — carries a {@code users.id}, never a
 * {@code provider_profiles.id}. The ledger rows this controller reads are
 * keyed by the user id, and {@code ownsProvider} resolves in the users.id
 * space, so the id passed to the service must be the authenticated user's id
 * — exactly the seam {@code ProviderStatsController} already documents and
 * implements. The previous resolution passed {@code provider_profiles.PK}:
 * {@code ownsProvider} then searched that PK inside {@code users.id}, found
 * nothing, and the legitimate owner was ALWAYS denied (403) — the measured
 * battery fingerprint. The profile lookup stays as the existence gate (404
 * when the caller has no provider profile) but its PK is never used as the
 * cross-module id.
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
    @Operation(summary = "Get my ledger balance",
            description = "The calling provider's current ledger balance in minor units — the "
                    + "amount credited from completed payments.")
    public ResponseEntity<ProviderBalance> getMyBalance(Authentication authentication) {
        return ResponseEntity.ok(ledgerService.getBalanceForOwner(requireOwnProviderUserId(authentication)));
    }

    @GetMapping("/providers/me/ledger/statement")
    @Operation(summary = "Get my ledger statement",
            description = "Paginated ledger movements for the calling provider.")
    public ResponseEntity<PagedResponse<LedgerEntryResponse>> getMyStatement(Authentication authentication,
                                                                             Pageable pageable) {
        UUID providerUserId = requireOwnProviderUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                ledgerService.getStatementForOwner(providerUserId, pageable).map(LedgerEntryResponse::from)));
    }

    /**
     * The "me" provider id in the CROSS-MODULE space: per the AuthHelper A1
     * contract, every {@code provider_id} column (ledger included) carries a
     * {@code users.id} — so the id the ledger reads aggregate by IS the
     * authenticated user's id. The lookup verifies the user actually HAS a
     * provider profile (404 otherwise, the house answer), and the service's
     * own {@code @authHelper.ownsProvider} guard re-resolves independently —
     * two checks, two resolutions, neither trusting the other. Identical seam
     * and wording to {@code ProviderStatsController#requireOwnProviderUserId}.
     */
    private UUID requireOwnProviderUserId(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        providerLookupPort.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No provider profile for the current user"));
        return userId;
    }
}
