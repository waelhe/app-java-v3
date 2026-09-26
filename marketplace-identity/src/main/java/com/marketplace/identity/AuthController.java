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
 */
@RestController
@RequestMapping(value = ApiConstants.AUTH, version = "1.0")
public class AuthController {

    private final UserService userService;
    private final UserMapper userMapper;

    public AuthController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
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
