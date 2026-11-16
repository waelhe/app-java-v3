package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the campaign's
 * owner-facing REST surface under {@code /api/v1/providers/me/ads} —
 * the {@code ProviderLedgerController} route convention (the owner's own
 * money surfaces live under providers/me). Every write is PROVIDER-gated
 * and ownership-checked in the service; the public half of the wave (the
 * click recording) lives in {@link AdClickController}.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Tag(name = "Ads", description = "W5 — the paid promotion engine: campaigns, budgets and the immutable billing history")
public class AdCampaignController {

    private final AdCampaignService campaignService;

    public AdCampaignController(AdCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @PostMapping("/providers/me/ads/campaigns")
    @Operation(summary = "Start a paid promotion for my listing",
            description = "One campaign promotes ONE listing with ONE budget: both event prices bill "
                    + "(clicks and impressions), the consumption caps at the remaining budget, and the "
                    + "campaign ends the moment the budget runs out. Only an ACTIVE listing I own can be "
                    + "promoted, and only one live campaign per listing exists at a time.")
    public ResponseEntity<AdCampaignService.AdCampaignView> create(
            @Valid @RequestBody AdCampaignService.CreateAdCampaignRequest request,
            Authentication authentication) {
        AdCampaignService.AdCampaignView view = campaignService.create(request, authentication);
        return ResponseEntity.created(java.net.URI.create(
                        ApiConstants.API_V1 + "/providers/me/ads/campaigns/" + view.id()))
                .body(view);
    }

    @GetMapping("/providers/me/ads/campaigns")
    @Operation(summary = "List my campaigns with the frozen money state",
            description = "Every campaign I own, newest first: budget, consumed (always exactly the sum "
                    + "of the frozen charge rows), remaining, status, and the billing marker.")
    public List<AdCampaignService.AdCampaignView> listMine(Authentication authentication) {
        return campaignService.listMine(authentication);
    }

    @PostMapping("/providers/me/ads/campaigns/{campaignId}/pause")
    @Operation(summary = "Pause my campaign",
            description = "The owner's hold: the listing loses the paid boost and nothing accrues — the "
                    + "paused gap never bills (the resume jumps the billing marker past the dark days).")
    public AdCampaignService.AdCampaignView pause(@PathVariable UUID campaignId,
                                                  Authentication authentication) {
        return campaignService.pause(campaignId, authentication);
    }

    @PostMapping("/providers/me/ads/campaigns/{campaignId}/resume")
    @Operation(summary = "Resume my campaign",
            description = "The hold lifts: the boost returns and the billing starts from the resume day — "
                    + "the dark days in between were never billable.")
    public AdCampaignService.AdCampaignView resume(@PathVariable UUID campaignId,
                                                   Authentication authentication) {
        return campaignService.resume(campaignId, authentication);
    }

    @GetMapping("/providers/me/ads/campaigns/{campaignId}/charges")
    @Operation(summary = "Read my campaign's immutable billing history",
            description = "The frozen charge rows, newest window first: every window's impressions, "
                    + "clicks and the amount that hit the budget — insert-only records, no surface "
                    + "anywhere can ever answer anything but the frozen truth.")
    public List<AdCampaignService.AdBillingChargeView> charges(@PathVariable UUID campaignId,
                                                               Authentication authentication) {
        return campaignService.charges(campaignId, authentication);
    }
}
