package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10, D-03) — the general read outlet of the
 * multi-role model: the caller's OWN active role assignments. The
 * mutating commands stay at the service boundary (the ADMIN gate, the
 * A-07 three-layer pattern) — there is no admin HTTP surface in this
 * wave; this controller is the minimum public face the task's scope
 * names, behind the resource-server chain's
 * {@code anyRequest().authenticated()} (no security-config change — the
 * {@code FollowController} house shape verbatim).
 *
 * <p>The entity never crosses the wire boundary (the
 * controllersMustNotDependOnJpaEntities gate) — the response speaks
 * {@link RoleAssignmentView} only. The identity resolution rides the
 * caller's authenticated principal (the /me-family rule: identity from
 * the authentication itself, never from a blind body or path).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class RoleAssignmentController {

    private final RoleAssignmentService roleAssignmentService;
    private final CurrentUserProvider currentUserProvider;

    public RoleAssignmentController(RoleAssignmentService roleAssignmentService,
                                    CurrentUserProvider currentUserProvider) {
        this.roleAssignmentService = roleAssignmentService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The caller's own ACTIVE role assignments, newest first (the L32
     * deterministic order). The revoked history stays server-side (the
     * audit record) — the read answers what the account HOLDS, which is
     * the same set the V183 effective-authorities view unions at the
     * next token mint.
     */
    @GetMapping("/me/roles")
    @Operation(summary = "List my active role assignments",
            description = "The caller's own ACTIVE role assignments (the closed "
                    + "CONSUMER/PROVIDER/ADMIN vocabulary — D-03's multi-role model), newest first. "
                    + "Revoked assignments stay in the audit history and are not listed; the listed "
                    + "set is what the login chain unions into the account's authorities.")
    public ResponseEntity<List<RoleAssignmentView>> myRoles(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(roleAssignmentService.activeRoles(userId));
    }
}
