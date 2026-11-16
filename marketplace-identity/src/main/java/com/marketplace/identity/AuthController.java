package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S1/B1 (platform-readiness audit §6 gate B1 — the registration surface): the
 * AUTH surface — account self-service operations that precede any
 * authenticated session. Before this endpoint a new user could not enter the
 * platform at all: {@code auth_users} was only ever written by the (now
 * retired) admin seed and the tests — the audit's first barrier ("a beautiful
 * frontend over a system where not a single user can register").
 *
 * <p>The endpoint is ANONYMOUS by design (the SecurityConfig chain-2 line —
 * the public-POST precedent of the webhooks and the leads): the request's own
 * body IS the credential being created. Every other surface of this module
 * stays authenticated; registration is the door, not a window.
 *
 * <p><b>A-04 (official-compliance plan §6 wave A — A.1/A.2) adds the four
 * token-redemption surfaces to this same anonymous family, by the same
 * measured rule:</b> the password-reset request/complete pair and the
 * email-verification resend/complete pair all PRECEDE any authenticated
 * session by definition — the reset requester has forgotten the very
 * credential a session would need, and the unverified account's holder is
 * locked out by the hold. The request's proof is not a session — it is the
 * single-use, time-limited V112 token the mail leg delivered out of band
 * (the OWASP-declared redemption contract).</p>
 */
@RestController
@RequestMapping(value = ApiConstants.AUTH, version = "1.0")
public class AuthController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(UserService userService, UserMapper userMapper,
                          PasswordResetService passwordResetService,
                          EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Self-service registration: BOTH stores in one transaction (the domain
     * row + the login rows), the password encoded by the delegating encoder
     * before any persistence, the role fixed at CONSUMER. The response is the
     * created profile — the same shape {@code GET /users/me} answers after
     * login, so a client can render the account immediately. The NEXT step
     * for the caller is the login gate itself (the documented PKCE flow).
     */
    @PostMapping("/register")
    @Operation(summary = "Register a new account (public)", description = "Creates the account "
            + "in one transaction — the profile row and the login rows — with the CONSUMER role. "
            + "The email becomes the login username and is capped at 50 characters (the login "
            + "store's domain). The password is stored only in its encoded form (bcrypt); 8 to "
            + "72 characters (the bcrypt byte ceiling — longer input is rejected, never "
            + "silently truncated). An address that already owns an account answers 409.")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User created = userService.register(
                request.email().trim(), request.password(), request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(userMapper.toResponse(created));
    }

    /**
     * A-04 (A.1) — the reset REQUEST: the enumeration-safe surface. The
     * answer is a CONSTANT 202 for every caller — an address that owns a
     * native account (a token is issued and the mail event published),
     * an unknown address, an OIDC-only account (no login row — no
     * password to reset), and a throttled re-request all take the same
     * shape and the same status, because the measured OWASP line is the
     * contract: "Ensure that responses return in a consistent amount of
     * time to prevent an attacker enumerating which accounts exist."
     * The body is deliberately empty — there is nothing this surface may
     * honestly say beyond "accepted".
     */
    @PostMapping("/password-reset/request")
    @Operation(summary = "Request a password reset (public)", description = "Accepts the request "
            + "with 202 for every address — the enumeration-safe contract: whether the address owns "
            + "an account or not, the response never differs. For an address that owns a native "
            + "login account a single-use, time-limited token (30 minutes by default) is issued and "
            + "a reset email carrying the deep link is sent. A re-request inside the per-account "
            + "60-second throttle window re-issues nothing and sends nothing.")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody ResetRequestRequest request) {
        passwordResetService.requestReset(request.email().trim());
        return ResponseEntity.accepted().build();
    }

    /**
     * A-04 (A.1) — the reset REDEMPTION: the token from the email's deep
     * link plus the replacement password. The single-use wall answers the
     * honest 400 (unknown token, already redeemed, expired); a valid
     * redemption re-encodes the new secret through the same delegating
     * encoder registration uses, writes it through the framework manager,
     * and kills every authorization issued for the account (a refresh
     * token minted under the old password does not survive the rotation).
     * 204 — there is nothing to return but the fact.
     */
    @PostMapping("/password-reset/complete")
    @Operation(summary = "Redeem a password reset token (public)", description = "Consumes the "
            + "single-use token from the reset email's link and replaces the account's password. "
            + "The replacement follows the same policy as registration: 8 to 72 characters (the "
            + "bcrypt byte ceiling). An unknown, already-used, or expired token answers 400. "
            + "All authorizations issued for the account are revoked with the change.")
    public ResponseEntity<Void> completePasswordReset(@Valid @RequestBody ResetCompleteRequest request) {
        passwordResetService.completeReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * A-04 (A.2) — the verification RESEND: the enumeration-safe surface's
     * twin. The constant 202 covers every caller — a pending account (a
     * fresh mail event is published), an unknown address, a grandfathered
     * or already-verified account, and an administratively consumed one.
     * Only a PENDING account (a live — expired or not — latest V112
     * verification row) is re-armed; a banned account can never be
     * re-armed through this surface (the ban-vs-verification invariant).
     */
    @PostMapping("/email-verification/resend")
    @Operation(summary = "Resend the email verification (public)", description = "Accepts the "
            + "request with 202 for every address — the enumeration-safe contract. A fresh "
            + "verification email (single-use, time-limited link, 24 hours by default) is sent "
            + "only when the address owns an account still awaiting verification; every other "
            + "case takes the same silent path.")
    public ResponseEntity<Void> resendEmailVerification(@Valid @RequestBody ResetRequestRequest request) {
        emailVerificationService.resend(request.email().trim());
        return ResponseEntity.accepted().build();
    }

    /**
     * A-04 (A.2) — the verification REDEMPTION: the token from the welcome
     * mail's deep link lifts the registration hold — {@code enabled=true}
     * through the framework manager, every other stored flag and the
     * encoded password replayed verbatim. The single-use wall answers the
     * honest 400 exactly like the reset redemption. 204: the account is
     * now loginable, and the login gate itself is the proof the caller
     * will use next.
     */
    @PostMapping("/email-verification/complete")
    @Operation(summary = "Redeem an email verification token (public)", description = "Consumes "
            + "the single-use token from the verification email's link and lifts the registration "
            + "hold — the account becomes loginable through the documented PKCE flow. An unknown, "
            + "already-used, or expired token answers 400 (an expired link is re-mintable through "
            + "the resend surface).")
    public ResponseEntity<Void> completeEmailVerification(@Valid @RequestBody VerificationCompleteRequest request) {
        emailVerificationService.completeVerification(request.token());
        return ResponseEntity.noContent().build();
    }

    /**
     * The shared request shape of the two anonymous "send me the mail"
     * surfaces (reset request, verification resend) — the register
     * surface's own email contract: the 50-character cap is the login
     * store's domain, and the clean 400 answers a malformed address
     * before any store is consulted.
     */
    record ResetRequestRequest(

            @NotBlank
            @Email
            @Size(max = 50)
            String email
    ) {
    }

    /**
     * The reset redemption request: the raw one-time token plus the
     * replacement password under the SAME policy {@code RegisterRequest}
     * pins (8..72 — the bcrypt byte ceiling; longer input is rejected,
     * never silently truncated).
     */
    record ResetCompleteRequest(

            @NotBlank
            String token,

            @NotBlank
            @Size(min = 8, max = 72)
            String newPassword
    ) {
    }

    /**
     * The verification redemption request: the raw one-time token alone —
     * the account's identity comes from the token's own row (the V112
     * redemption right), never from a caller-supplied address.
     */
    record VerificationCompleteRequest(

            @NotBlank
            String token
    ) {
    }

    /**
     * The registration request. The email is capped at 50 characters — the
     * login store's own domain (auth_users.username VARCHAR(50), V13): the
     * address becomes the username, and the cap is validated HERE (the clean
     * 400) rather than dying at storage time (a 500 — the CI-measured
     * failure this round closed). The password policy is deliberately
     * exactly what the storage can honor: a minimum of 8 (the baseline every
     * guidance converges on) and a maximum of 72 BYTES — bcrypt's documented
     * ceiling; a longer password is REJECTED here rather than silently
     * truncated by the hash (the honest contract — the stored verifier always
     * covers the whole accepted password). No composition rules: length is
     * the complexity that survives real adversaries, and the encoder does the
     * rest.
     */
    record RegisterRequest(

            @NotBlank
            @Email
            @Size(max = 50)
            String email,

            @NotBlank
            @Size(min = 8, max = 72)
            String password,

            @Size(max = 100)
            String displayName
    ) {
    }
}
