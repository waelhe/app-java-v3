package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.util.List;
import java.util.UUID;

/**
 * W5b (the journeys register's J29 row — تفويض فريق مزود الخدمة بصلاحيات
 * ونطاق): the owner's team-delegation surface — one controller, three
 * endpoints, the register's own CONFIRMED GAP closed: «تفويض فريق/فرع
 * بصلاحيات ونطاق — غير منفذ ← W5/W11».
 *
 * <p>The caller identity rides the house convention: the request-scoped
 * authentication resolves through {@link CurrentUserProvider} (the
 * L41/L42 community pattern) — the owner is WHO the token resolves to,
 * and the service's gate checks that user IS the profile's owner. The
 * role-level guard ({@code hasRole('PROVIDER')}) lives on the service
 * with the module's other write surfaces.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderTeamController {

    private final ProviderTeamService teamService;
    private final CurrentUserProvider currentUserProvider;

    public ProviderTeamController(ProviderTeamService teamService,
                                  CurrentUserProvider currentUserProvider) {
        this.teamService = teamService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The delegation grant's request shape: the member (a user id — the
     * identity module's own surface remains the only place it resolves)
     * and the role class. The pattern pins the vocabulary at the boundary
     * (the service re-validates — defense in depth, the V234 CHECK is the
     * floor).
     */
    public record TeamMemberRequest(
            @Schema(description = "The user id receiving the delegated seat", example = "8f2f5c1e-77c1-4a4e-9d3b-1c99d05a1111")
            @NotNull UUID memberUserId,
            @Schema(description = "The delegated authority's class — MANAGER (the senior seat) "
                    + "or EDITOR (the working seat)", example = "EDITOR")
            @NotBlank @Pattern(regexp = "MANAGER|EDITOR",
                    message = "role must be MANAGER or EDITOR") String role) {
    }

    @PostMapping("/providers/{id}/team")
    @Operation(summary = "Add a team member", description = "W5b (J29): the owner delegates "
            + "one seat — the member gains the acting-provider authority (role MANAGER or "
            + "EDITOR) on the declared profile and business-page surfaces. The whole guard "
            + "family runs before the write: unknown provider 404; a non-owner actor 403; "
            + "the owner as member 409 (the owner needs no delegation); a duplicate live "
            + "seat 409 — the partial unique index backs the concurrent race.")
    public ResponseEntity<ProviderTeamMemberResponse> addMember(@PathVariable UUID id,
                                                                @Valid @RequestBody TeamMemberRequest request,
                                                                Authentication authentication) {
        UUID actingUserId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProviderTeamMemberResponse.from(
                teamService.addMember(id, actingUserId, request.memberUserId(), request.role(),
                        actingUserId.toString())));
    }

    @GetMapping("/providers/{id}/team")
    @Operation(summary = "List my provider's team", description = "W5b (J29): the owner's "
            + "roster read — the live (ACTIVE) seats in arrival order. Revoked seats live "
            + "in the Envers trail, not the roster (the one-live-seat law: a re-add after "
            + "revocation is a NEW seat).")
    public ResponseEntity<List<ProviderTeamMemberResponse>> team(@PathVariable UUID id,
                                                                 Authentication authentication) {
        UUID actingUserId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(teamService.team(id, actingUserId).stream()
                .map(ProviderTeamMemberResponse::from).toList());
    }

    @DeleteMapping("/providers/{id}/team/{memberId}")
    @Operation(summary = "Revoke a team member", description = "W5b (J29): the owner "
            + "withdraws one seat — ACTIVE → REVOKED, the only exit; the row and its "
            + "Envers trail stay, the authority ends at commit (every delegated write "
            + "fails from the next request on). Only an ACTIVE seat can be revoked — "
            + "a second revocation answers 409.")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, @PathVariable UUID memberId,
                                       Authentication authentication) {
        UUID actingUserId = currentUserProvider.getCurrentUserId(authentication);
        teamService.revoke(id, actingUserId, memberId);
        return ResponseEntity.noContent().build();
    }
}
