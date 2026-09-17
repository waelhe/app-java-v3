package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * L41 (neighborhood community plan §5 — the membership anchor): the
 * authenticated {@code /me} surface. The caller's user id IS the owner
 * key (the plain {@code /me} seam — no profile indirection); every
 * endpoint sits behind the resource-server chain's
 * {@code anyRequest().authenticated()} — no security-config change, the
 * same zero-config line every /me layer since L20 has ridden.
 *
 * <p>PUT is join-or-switch with honest status semantics: 201 Created
 * when a membership row was created (a join, or a switch — the old row
 * is soft-deleted and a new one lands), 200 OK when the stored row
 * already answered (the idempotent re-join).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NeighborhoodMembershipController {

    private final NeighborhoodMembershipService membershipService;
    private final CurrentUserProvider currentUserProvider;

    public NeighborhoodMembershipController(NeighborhoodMembershipService membershipService,
                                            CurrentUserProvider currentUserProvider) {
        this.membershipService = membershipService;
        this.currentUserProvider = currentUserProvider;
    }

    @PutMapping("/me/neighborhood")
    @Operation(summary = "Join (or switch to) my neighborhood",
            description = "Declares the caller's home neighborhood: a level-3 node of the "
                    + "administrative geo tree. Joining a different neighborhood switches — "
                    + "the old membership is soft-deleted and a fresh one (with a fresh "
                    + "memberSince) is created atomically. Re-joining the current "
                    + "neighborhood is an idempotent no-op (200 with the stored row). "
                    + "An unknown locationId answers 404; a level 0-2 node answers 400 — "
                    + "both before any write. The verification state is SELF_DECLARED "
                    + "(the verification method is a pending product gate).")
    public ResponseEntity<NeighborhoodMembershipView> join(
            @Valid @RequestBody NeighborhoodJoinRequest request,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        var result = membershipService.join(userId, request.locationId());
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.view());
    }

    @GetMapping("/me/neighborhood")
    @Operation(summary = "Read my neighborhood membership",
            description = "The caller's ACTIVE membership — 404 when none exists (join first).")
    public ResponseEntity<NeighborhoodMembershipView> getMine(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(membershipService.getMine(userId));
    }

    @DeleteMapping("/me/neighborhood")
    @Operation(summary = "Leave my neighborhood",
            description = "Soft-deletes the caller's ACTIVE membership — the one-membership "
                    + "slot is released for a later re-join. 404 when there is no "
                    + "membership to leave.")
    public ResponseEntity<Void> leave(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        membershipService.leave(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The join body: the level-3 geo node id — the only fact a join
     * carries (everything else is the system's own: who, when, what
     * state).
     */
    public record NeighborhoodJoinRequest(
            @NotNull
            @Schema(description = "The geo tree node id — must be a level-3 neighborhood "
                    + "(resolve candidates via GET /api/v1/geo).", example = "11111111-1111-4111-8111-111111111104")
            UUID locationId
    ) {
    }
}
