package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * JT-20 (the discovery waves — AC-20-05): the generalized /me follows
 * surface — the {@code ProviderFollowController} house shape (the
 * caller's user id IS the owner key, every endpoint behind the
 * resource-server chain's {@code anyRequest().authenticated()} — no
 * security-config change; the entity never crosses the wire boundary —
 * the controllersMustNotDependOnJpaEntities gate).
 *
 * <p><b>Path shape (measured, not preferred):</b> the standing
 * {@code POST/GET /me/follows} paths BELONG to the provider follow
 * surface (V93 — untouched by contract), and Spring MVC answers two
 * controllers claiming one path+method with an ambiguous-mapping boot
 * failure — so the generalized surface scopes its resource by the TYPE
 * segment and the pair rides the path
 * ({@code /me/follows/{followableType}/{followableId}}), the
 * {@code ListingFavoritesController} {@code /me/favorites/{listingId}}
 * house precedent one segment deeper. The provider surface's paths
 * answer exactly as they always have; the DELETE's pair shape is the
 * wave's own and cannot collide with the provider's single-segment
 * {@code /me/follows/{id}} (two segments vs one — Spring's best-match
 * routing keeps both honest).
 *
 * <p>The request's {@code followableType} is parsed HERE (not bound as
 * an enum path variable) so an unknown type answers the honest 400 with
 * the closed vocabulary spelled out — PROVIDER included, pointing the
 * client back at the provider follow's own surface.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class FollowController {

    private final FollowService followService;
    private final CurrentUserProvider currentUserProvider;

    public FollowController(FollowService followService,
                            CurrentUserProvider currentUserProvider) {
        this.followService = followService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Follow one source. 201 on a fresh create, 200 on a live replay —
     * the honest status pair for an idempotent POST (the standing row
     * answers either way).
     */
    @PostMapping("/me/follows/{followableType}/{followableId}")
    @RateLimiter(name = "providerFollowCreate")
    @Operation(summary = "Follow a user or a group",
            description = "Stores the caller's follow of one followable source (USER or GROUP — the "
                    + "closed vocabulary; the source id sits in the type's own id space: USER → the "
                    + "users id, GROUP → the neighborhood group id). Following yourself answers 400; "
                    + "an unknown source answers 404; a live replay answers the standing row (the "
                    + "idempotent contract — POST is 'make sure I follow X'). Provider follows keep "
                    + "their own /me/follows surface (V93, untouched). Unfollow and re-follow are "
                    + "legal.")
    public ResponseEntity<FollowView> create(
            @PathVariable
            @Schema(description = "The followable source type — the closed vocabulary USER | GROUP",
                    example = "USER")
            String followableType,
            @PathVariable
            @Schema(description = "The source id, in the type's own id space",
                    example = "9f8c3e2a-1111-4c2a-9b7e-0d5f6a1b2c3d")
            UUID followableId,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        FollowService.FollowWriteResult result = switch (parseType(followableType)) {
            case USER -> followService.followUser(userId, followableId);
            case GROUP -> followService.followGroup(userId, followableId);
        };
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.view());
    }

    /**
     * The member's own follows of ONE type — the generalized surface's
     * list (the provider follows' list keeps its own standing path;
     * the rail's cross-type union is the in-process
     * {@code FollowedSourcesPort}, not an HTTP shape).
     */
    @GetMapping("/me/follows/{followableType}")
    @Operation(summary = "List my follows of one type",
            description = "The caller's follows of the given type (USER or GROUP), newest first "
                    + "(deterministic order). The pair is raw — the source id in its own id space; "
                    + "the discovery rail composes the display cards on its side. Unknown types "
                    + "answer 400.")
    public ResponseEntity<List<FollowView>> list(
            @PathVariable
            @Schema(description = "The followable source type — the closed vocabulary USER | GROUP",
                    example = "GROUP")
            String followableType,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(followService.listFollows(userId, parseType(followableType)));
    }

    /**
     * Unfollow one source — IDEMPOTENT: a pair you do not hold is a
     * quiet 204 (the DELETE's contract is "make sure I don't follow X"),
     * and the freed triple accepts a fresh follow again.
     */
    @DeleteMapping("/me/follows/{followableType}/{followableId}")
    @Operation(summary = "Unfollow a user or a group",
            description = "Soft-deletes the caller's follow of the pair — idempotent: a pair you do "
                    + "not hold is a quiet no-op (204 either way). The soft delete frees the "
                    + "(member, type, source) triple, so following the same source again is legal.")
    public ResponseEntity<Void> delete(
            @PathVariable
            @Schema(description = "The followable source type — the closed vocabulary USER | GROUP",
                    example = "USER")
            String followableType,
            @PathVariable
            @Schema(description = "The source id, in the type's own id space",
                    example = "9f8c3e2a-1111-4c2a-9b7e-0d5f6a1b2c3d")
            UUID followableId,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        switch (parseType(followableType)) {
            case USER -> followService.unfollowUser(userId, followableId);
            case GROUP -> followService.unfollowGroup(userId, followableId);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The honest 400 for an out-of-vocabulary type — the message spells
     * the closed set and points the PROVIDER leg back at its own surface
     * (the one-home rule, readable from the wire).
     */
    private static FollowableType parseType(String raw) {
        try {
            return FollowableType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Unknown followable type: " + raw
                    + " — the live vocabulary is USER, GROUP (provider follows keep their own "
                    + "/me/follows surface)");
        }
    }
}
