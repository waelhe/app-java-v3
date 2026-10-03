package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the public
 * click-recording surface — the plan's «نقرة مسجلة». The frontend calls
 * it when a visitor clicks a promoted result; attribution resolves the
 * listing's ONE live campaign, so the unpromoted click answers the
 * honest 404 no-op. {@code permitAll} — the public lead-submission POST
 * precedent ({@code listings/{*}/leads}, SecurityConfig's anonymous
 * lane); the visitor dedup is the counter's own structural business,
 * never the caller's.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Tag(name = "Ads", description = "W5 — the paid promotion engine: the public click recording")
public class AdClickController {

    private final AdClickCounter clickCounter;

    public AdClickController(AdClickCounter clickCounter) {
        this.clickCounter = clickCounter;
    }

    @PostMapping("/ads/listings/{listingId}/clicks")
    @Operation(summary = "Record a promoted-result click (public)",
            description = "Attributes one visitor click to the listing's live campaign — the click the "
                    + "billing window later freezes into the immutable charge. One visitor counts once "
                    + "per rolling window (the keyed-fingerprint dedup). A listing with no live campaign "
                    + "answers 404: the click on an unpromoted listing is nobody's to bill.")
    public ResponseEntity<Map<String, UUID>> recordClick(@PathVariable UUID listingId,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        return clickCounter.recordClick(listingId, request.getRemoteAddr())
                .<ResponseEntity<Map<String, UUID>>>map(campaignId ->
                        ResponseEntity.ok(Map.of("campaignId", campaignId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
