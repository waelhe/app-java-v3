package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): the provider's own stats —
 * {@code GET /api/v1/providers/me/stats?from&to}. The "me" provider is
 * resolved from the authenticated user via {@link ProviderLookupPort} (the
 * client never supplies a provider id — the L20 seam), and the service call
 * is guarded by the unit's ownership convention
 * ({@code @authHelper.ownsProvider} on {@code ProviderStatsService#getStats})
 * — resolution and authorization are two independent checks.
 *
 * <p>Window parameters are optional: omitted both = the default last 30
 * days. The {@link StatsWindow} record is the input gate — an incomplete,
 * reversed, zero-length or over-a-year window is a 400 at construction,
 * before any query (the L27 SearchCriteria lesson).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderStatsController {

    private final ProviderStatsService statsService;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;

    public ProviderStatsController(ProviderStatsService statsService,
                                   CurrentUserProvider currentUserProvider,
                                   ProviderLookupPort providerLookupPort) {
        this.statsService = statsService;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
    }

    @GetMapping("/providers/me/stats")
    @Operation(summary = "Get my host stats",
            description = "The provider's own occupancy, net revenue (after the documented "
                    + "commission) and completed bookings for the window. Omitted window = last "
                    + "30 days; max window = 1 year.")
    public ResponseEntity<ProviderStatsResponse> getMyStats(
            @Parameter(description = "Window start (inclusive), ISO-8601 instant — both bounds "
                    + "must be present together", example = "2026-09-01T00:00:00Z")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Window end (inclusive), ISO-8601 instant — both bounds "
                    + "must be present together", example = "2026-09-30T23:59:59Z")
            @RequestParam(required = false) Instant to,
            Authentication authentication) {
        StatsWindow window = (from == null && to == null)
                ? StatsWindow.lastThirtyDays(Instant.now())
                : new StatsWindow(from, to);
        return ResponseEntity.ok(statsService.getStats(requireOwnProviderUserId(authentication), window));
    }

    /**
     * The "me" provider id in the CROSS-MODULE space: per the AuthHelper A1
     * contract, every provider_id column (availability, ledger, booking)
     * carries a {@code users.id} — so the id the stats aggregate by IS the
     * authenticated user's id. The lookup verifies the user actually HAS a
     * provider profile (404 otherwise, the house answer), and the service's
     * own {@code @authHelper.ownsProvider} guard re-resolves independently
     * — two checks, two resolutions, neither trusting the other.
     */
    private UUID requireOwnProviderUserId(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        providerLookupPort.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No provider profile for the current user"));
        return userId;
    }
}
