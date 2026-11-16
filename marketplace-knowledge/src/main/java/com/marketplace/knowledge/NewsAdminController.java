package com.marketplace.knowledge;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * D-3 (JT-19/D-30 — «أخبار محلية»): the news administrative surface, on
 * the {@code InstitutionAdminController} house shape — the class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} gate (the Security
 * method-security reference's own channel; the {@code /api/v1/admin/**}
 * matcher rides beneath it), the outlet's registration (201, born
 * UNVERIFIED — the honest registry) and its verification verdict's ONLY
 * mover (APPROVE/REJECT), the publication from a VERIFIED outlet only
 * (201, born displayed), the correction (200 — the item stays displayed,
 * the REQUIRED note lands with it, the original never muted), and the
 * withdrawal (204 — the public reads stop returning the item the moment
 * the flag lands, AC-20-10).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class NewsAdminController {

    private final NewsService newsService;

    public NewsAdminController(NewsService newsService) {
        this.newsService = newsService;
    }

    @PostMapping("/news/publishers")
    @Operation(summary = "Register a news publisher (admin)",
            description = "The outlet's honest registration — born UNVERIFIED (the trust mark arrives "
                    + "only through the verification verdict; an UNVERIFIED outlet cannot publish news).")
    public ResponseEntity<NewsPublisherResponse> createPublisher(
            @Valid @RequestBody NewsPublisherRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(newsService.createPublisher(request));
    }

    @PostMapping("/news/publishers/{publisherId}/verification")
    @Operation(summary = "Approve or reject a news publisher's verification (admin)",
            description = "The verdict's ONLY mover, in both directions. APPROVE moves the outlet to "
                    + "VERIFIED (the trust mark; also the recovery lever for a REJECTED one) and REJECT "
                    + "refuses an UNVERIFIED claim (the row stays — the honest registry). Any other "
                    + "source state answers 409 with the entity's own transition words. Only a VERIFIED "
                    + "publisher can publish news items.")
    public ResponseEntity<NewsPublisherResponse> verifyPublisher(
            @PathVariable UUID publisherId, @RequestParam String decision) {
        boolean approve = switch (decision.trim()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new BadRequestException("Invalid decision '" + decision
                    + "' — expected APPROVE or REJECT");
        };
        return ResponseEntity.ok(newsService.verifyPublisher(publisherId, approve));
    }

    @PostMapping("/news/items")
    @Operation(summary = "Publish a news item from a VERIFIED publisher (admin)",
            description = "The publication gate ladder: the outlet's existence (404 — the FK's own "
                    + "seam), its VERIFIED state (409 — the delegated-source condition), the optional "
                    + "geo scope (404 unknown node / 400 non-neighborhood node, before any write). The "
                    + "attribution pair (original link + original date) is required and rides every "
                    + "read forever (AC-20-09). The item is born displayed — no staged publication state.")
    public ResponseEntity<NewsItemResponse> createNews(
            @Valid @RequestBody NewsItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(newsService.createNews(request));
    }

    @PatchMapping("/news/items/{itemId}")
    @Operation(summary = "Correct a news item (admin)",
            description = "The correction — the knowledge revise shape with the honesty markers: the "
                    + "complete new content re-submits, the REQUIRED note lands in the same write "
                    + "(corrected/correctedAt/correctionNote), and the item STAYS DISPLAYED — the "
                    + "correction is shown, never the muting (AC-20-10). The original publication date "
                    + "and the geo scope are immutable (the routing-mismatch 400). A withdrawn item "
                    + "answers 409.")
    public ResponseEntity<NewsItemResponse> correctNews(
            @PathVariable UUID itemId,
            @Valid @RequestBody NewsItemCorrectionRequest request) {
        return ResponseEntity.ok(newsService.correctNews(itemId, request));
    }

    @DeleteMapping("/news/items/{itemId}")
    @Operation(summary = "Withdraw a news item (admin)",
            description = "The withdrawal — the domain flag lands with its timestamp in the same "
                    + "transaction: the public board and detail stop returning the item immediately "
                    + "(AC-20-10). The row and its audit trail are kept. A second withdrawal answers 409.")
    public ResponseEntity<Void> withdrawNews(@PathVariable UUID itemId) {
        newsService.withdrawNews(itemId);
        return ResponseEntity.noContent().build();
    }
}
