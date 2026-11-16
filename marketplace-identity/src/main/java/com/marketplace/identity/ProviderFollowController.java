package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — G21): the authenticated /me follows surface —
 * the {@code SavedSearchController} house shape (the caller's user id IS
 * the owner key, the plain {@code /me} seam, every endpoint behind the
 * resource-server chain's {@code anyRequest().authenticated()} — no
 * security-config change). The HTTP boundary speaks the
 * {@code ProviderFollowView} record only — the service composes the views
 * (the {@code controllersMustNotDependOnJpaEntities} gate: the entity
 * never crosses the wire boundary).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderFollowController {

    private final ProviderFollowService providerFollowService;
    private final CurrentUserProvider currentUserProvider;

    public ProviderFollowController(ProviderFollowService providerFollowService,
                                    CurrentUserProvider currentUserProvider) {
        this.providerFollowService = providerFollowService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The named rate-limiter instance (the savedSearchCreate L29/L35 model:
     * fail fast, 429 RL-001, conservative until real traffic) — a follow is
     * a cheap write, and the bridge's per-activation fan-out is bounded by
     * the stored set this write grows.
     */
    @PostMapping("/me/follows")
    @RateLimiter(name = "providerFollowCreate")
    @Operation(summary = "Follow a provider",
            description = "Stores the caller's follow of a provider (the request's providerId is the "
                    + "provider public page's own key). When that provider's listing is announced "
                    + "(activated), the follower receives exactly one FOLLOWED_PROVIDER_NEW_LISTING "
                    + "alert — in-app row always, WebSocket/email per the standing per-type/channel "
                    + "preferences. Following your own provider profile answers 400; a live duplicate "
                    + "answers 409. Unfollowing and re-following is legal.")
    public ResponseEntity<ProviderFollowView> create(
            @Valid @RequestBody FollowProviderRequest request,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(providerFollowService.create(userId, request.providerId()));
    }

    @GetMapping("/me/follows")
    @Operation(summary = "List my followed providers",
            description = "The caller's follows, newest first (deterministic order), each row composed "
                    + "with the followed provider's current public identity — ONE batch provider "
                    + "resolution for the whole page (the W1 findAllByIds N+1 rule), never a lookup "
                    + "per row.")
    public ResponseEntity<PagedResponse<ProviderFollowView>> list(
            Pageable pageable, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(providerFollowService.listFor(userId, pageable)));
    }

    @DeleteMapping("/me/follows/{id}")
    @Operation(summary = "Unfollow a provider",
            description = "Soft-deletes the caller's follow. A foreign id is a 404 — it is not in "
                    + "your list. The pair is freed, so following the same provider again is legal.")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        providerFollowService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** The request shape: the provider PROFILE id (the public page's key). */
    public record FollowProviderRequest(
            @NotNull
            @Schema(description = "The provider to follow — the provider public page's own id",
                    example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            UUID providerId
    ) {
    }
}
