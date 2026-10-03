package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — G28): the public reviewer page — the
 * {@code /providers/{id}/public} house shape on the identity module's own
 * path family ({@code /api/v1/users}): the plain {@code /users/me} owner
 * surfaces keep their authenticated contracts; only the composite public
 * page opens (the SecurityConfig precise-wildcard line, the L36 precedent
 * verbatim). No {@code Authentication} parameter — the anonymous read the
 * click-from-a-review needs.
 */
@RestController
@RequestMapping(value = ApiConstants.IDENTITY, version = "1.0")
public class ReviewerPublicProfileController {

    private final ReviewerPublicProfileService reviewerPublicProfileService;

    public ReviewerPublicProfileController(ReviewerPublicProfileService reviewerPublicProfileService) {
        this.reviewerPublicProfileService = reviewerPublicProfileService;
    }

    @GetMapping("/{id}/public")
    @Operation(summary = "Get a reviewer's public profile",
            description = "The public reviewer page the review rows link to — the display name "
                    + "(honouring closed accounts), the join timestamp, the verified/organic "
                    + "published-review counters, the cumulative helpful-vote count, and the derived "
                    + "credibility badges. An unknown reviewer answers 404; a live account with no "
                    + "published reviews is the honest zero profile.")
    public ResponseEntity<ReviewerPublicProfile> getPublicProfile(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewerPublicProfileService.getPublicProfile(id));
    }
}
