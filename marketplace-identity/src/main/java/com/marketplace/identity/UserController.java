package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = ApiConstants.IDENTITY, version = "1.0")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final UserDataExportService userDataExportService;

    public UserController(UserService userService, UserMapper userMapper,
                          UserDataExportService userDataExportService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.userDataExportService = userDataExportService;
    }

    /**
     * The plain {@link Authentication} parameter (house convention, every other
     * controller) — L23's gate test proved the previous
     * {@code @AuthenticationPrincipal JwtAuthenticationToken} parameter resolved
     * to {@code null} for real Bearer requests (the resolver returns the
     * {@code Jwt} principal; the type mismatch yields null), so every real
     * client call died with 500 INT-001.
     */
    @GetMapping("/me")
    @Operation(summary = "Get my profile", description = "The authenticated user's profile — the "
            + "bootstrap call after every login (roles, subject, display name).")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        User user = userService.syncFromOidc(authentication);
        return ResponseEntity.ok(userMapper.toResponse(user));
    }

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
     * surface, gate b-5): the self-service data-subject export. GET — a
     * pure read of what the controller holds on the requester; the response
     * is a structured, machine-readable JSON document whose boundary notice
     * documents the scope and date of the export (the auditable Art. 20
     * execution record).
     *
     * <p>The {@code syncFromOidc} first step is the /me bootstrap convention
     * verbatim: the export includes the profile, and the profile syncs from
     * the token's freshest claims before it is read (the same call every
     * other /me-family surface makes).
     */
    @GetMapping("/me/export")
    @Operation(summary = "Export my data (GDPR Art. 20)", description = "The authenticated "
            + "user's data-subject export — a structured, machine-readable JSON document of "
            + "the personal data they provided: profile, first-party bookings, authored reviews "
            + "and messages, media metadata, and notifications. The counterparty on shared "
            + "records appears as an opaque identifier only.")
    public ResponseEntity<UserDataExportResponse> exportMyData(Authentication authentication) {
        User user = userService.syncFromOidc(authentication);
        return ResponseEntity.ok(userDataExportService.exportFor(user));
    }
}
