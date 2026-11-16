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

import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider's own
 * listing-view analytics — {@code GET /api/v1/providers/me/listings/views?days=}.
 * The "me" provider is resolved from the authenticated user via
 * {@link ProviderLookupPort} (the client never supplies a provider id —
 * the L20 seam), and the service call is guarded by the unit's ownership
 * convention ({@code @authHelper.ownsProvider} on
 * {@code ProviderListingViewsService#getViews}) — resolution and
 * authorization are two independent checks, the exact
 * {@code ProviderStatsController} shape.
 *
 * <p>The {@code days} parameter is optional: omitted = the default 30-day
 * window; the whitelist is 7, 30 or 90, and anything else is a 400 raised
 * by the {@link ListingViewsWindow} type itself at construction, before
 * any query (the L27 lesson).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderListingViewsController {

    private final ProviderListingViewsService viewsService;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;

    public ProviderListingViewsController(ProviderListingViewsService viewsService,
                                          CurrentUserProvider currentUserProvider,
                                          ProviderLookupPort providerLookupPort) {
        this.viewsService = viewsService;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
    }

    @GetMapping("/providers/me/listings/views")
    @Operation(summary = "My listings' view analytics",
            description = "The calling provider's per-listing view totals for the window — "
                    + "deduplicated views (one per visitor per listing per day, a rolling "
                    + "24h fingerprint marker), summed over the window's UTC day buckets with "
                    + "today's still-accumulating bucket included. days must be 7, 30 or 90; "
                    + "omitted = 30. Listings appear when they have at least one view inside "
                    + "the window (most-viewed first, deterministic order); past views of "
                    + "paused/archived listings are history, not a secret.")
    public ResponseEntity<ProviderListingViewsResponse> getMyListingViews(
            @Parameter(description = "The window length in days — one of 7, 30 or 90; "
                    + "omitted = the default 30 days", example = "7")
            @RequestParam(required = false) Integer days,
            Authentication authentication) {
        ListingViewsWindow window = ListingViewsWindow.ofDays(days);
        return ResponseEntity.ok(
                viewsService.getViews(requireOwnProviderUserId(authentication), window));
    }

    /**
     * The "me" provider id in the CROSS-MODULE space: per the AuthHelper A1
     * contract, {@code provider_listings.provider_id} carries a
     * {@code users.id} — so the id the views aggregate by IS the
     * authenticated user's id. The lookup verifies the user actually HAS a
     * provider profile (404 otherwise, the house answer), and the service's
     * own {@code @authHelper.ownsProvider} guard re-resolves independently
     * — two checks, two resolutions, neither trusting the other (the
     * {@code ProviderStatsController} seam verbatim).
     */
    private UUID requireOwnProviderUserId(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        providerLookupPort.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No provider profile for the current user"));
        return userId;
    }
}
