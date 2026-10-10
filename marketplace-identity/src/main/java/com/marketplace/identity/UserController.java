package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = ApiConstants.IDENTITY, version = "1.0")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final UserDataExportService userDataExportService;
    private final AccountSelfDeletionService accountSelfDeletionService;

    /**
     * The official prescription for a custom logout endpoint, verbatim
     * (Spring Security reference, {@code servlet/authentication/logout.html}
     * — "Creating a Custom Logout Endpoint"): a plain
     * {@link SecurityContextLogoutHandler} the endpoint invokes after its own
     * action, "to ensure a secure and complete logout... Failing to call
     * SecurityContextLogoutHandler means that the SecurityContext could
     * still be available on subsequent requests, meaning that the user is
     * not actually logged out." Bytecode-verified against
     * spring-security-web 7.1.1: {@code logout()} invalidates the HTTP
     * session if one exists ({@code getSession(false)} — never creates),
     * clears the {@code SecurityContextHolderStrategy}, and saves an empty
     * context into the {@code SecurityContextRepository}.
     */
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public UserController(UserService userService, UserMapper userMapper,
                          UserDataExportService userDataExportService,
                          AccountSelfDeletionService accountSelfDeletionService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.userDataExportService = userDataExportService;
        this.accountSelfDeletionService = accountSelfDeletionService;
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

    /**
     * A-05 (official-compliance plan §6 wave A — A.3, the app-store
     * requirement; the pseudonymization plan's gate b-5 door, opened by the
     * owner's compliance order): the self-service account deletion — the
     * /me family's terminal surface. The account's own holder deletes the
     * account through the double verification the plan pins ("بمسار التحقق
     * المزدوج"): the live Bearer token AND the current password in the
     * request body, verified by {@link AccountSelfDeletionService} before
     * any store is touched, then executed by the standing I7 erasure
     * operation ({@code pseudonymizeAccount} — A.3's "GDPR purge قائم").
     *
     * <p><b>The logout leg — the official reference A.3 names this surface
     * for (Security {@code servlet/authentication/logout.html}, "Creating a
     * Custom Logout Endpoint"):</b> after the deletion the endpoint invokes
     * {@link SecurityContextLogoutHandler#logout} — the framework's own
     * complete-logout component, the exact prescription the reference gives
     * a custom Spring MVC logout endpoint ("you will need to have that
     * endpoint invoke Spring Security's SecurityContextLogoutHandler to
     * ensure a secure and complete logout"). On this stateless
     * resource-server chain the Bearer access token is self-contained and
     * dies by its documented 900s TTL; the refresh grant dies immediately
     * with the {@code oauth2_authorization} rows the erasure deletes; and
     * the handler's session invalidation covers any live form-login session
     * the request carries ({@code getSession(false)} — a no-op for the pure
     * Bearer call, the complete logout for a browser session).
     *
     * <p><b>The body on DELETE (RFC 9110 §9.3.5):</b> the RFC's "A client
     * SHOULD NOT generate content in a DELETE request unless it is made
     * directly to an origin server that has previously indicated, in or out
     * of band, that such requests have meaning" — this API IS that origin
     * server and its OpenAPI contract (this operation's request schema) IS
     * the out-of-band indication to the first-party clients. The
     * pseudonymization plan itself pinned this composition (DELETE
     * {@code /users/me} carrying the double-verification path).
     *
     * <p>204 — the redemption-surface precedent: there is nothing to return
     * but the fact. A wrong password answers 401 AUTHN-001 with zero
     * mutation; a token whose account is already deleted answers the honest
     * 404; the last-active-ADMIN guard answers 409 unchanged.
     */
    @DeleteMapping("/me")
    @Operation(summary = "Delete my account (app-store requirement)", description = "The "
            + "self-service account deletion — the double-verified trigger of the standing "
            + "erasure operation: the Bearer token plus the account's current password. "
            + "On success the account's direct identifiers are replaced (the pseudonymized "
            + "form), the login identity and every issued authorization die, and any live "
            + "session is invalidated — the complete logout. The UUID and the records "
            + "referencing it stay (reference integrity, the counterparties' rights, and "
            + "the declared retention window). A wrong password answers 401 and changes "
            + "nothing; an account already deleted answers 404.")
    public ResponseEntity<Void> deleteMyAccount(Authentication authentication,
                                                HttpServletRequest request,
                                                HttpServletResponse response,
                                                @Valid @RequestBody DeleteAccountRequest body) {
        // The house narrowing (syncFromOidc's own guard): the subject comes
        // from the JwtAuthenticationToken — the requester's own proof, never
        // a caller-supplied identifier.
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalArgumentException(
                    "Unsupported authentication type: " + authentication);
        }
        accountSelfDeletionService.deleteOwnAccount(
                token.getToken().getSubject(), body.password());

        // The official complete-logout prescription — AFTER the deletion
        // succeeded: a failed verification (401) must NOT terminate the
        // caller's authenticated state; the retry keeps its session.
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * The double-verification body: the account's current password alone —
     * the second proof. {@code @NotBlank} with no length policy on purpose:
     * this is VERIFICATION input compared against the stored verifier, not
     * a credential being set — the 8..72 policy governs what registration
     * and reset accept and store, and an input that mismatches the stored
     * verifier of any length answers the same 401.
     */
    record DeleteAccountRequest(

            @NotBlank
            String password
    ) {
    }
}
