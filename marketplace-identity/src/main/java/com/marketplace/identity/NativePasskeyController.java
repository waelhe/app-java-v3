package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.NativePasskeyChallengeResponse;
import com.marketplace.shared.security.NativePasskeyService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native passkey ceremony endpoints. Only assertion options are anonymous;
 * enrollment is tied to the already authenticated, email-verified account.
 */
@RestController
@RequestMapping(value = ApiConstants.AUTH, version = "1.0")
public class NativePasskeyController {

    private final NativePasskeyService passkeyService;

    public NativePasskeyController(NativePasskeyService passkeyService) {
        this.passkeyService = passkeyService;
    }

    @PostMapping("/passkeys/assertion/options")
    @RateLimiter(name = "passkeyAssertionOptions")
    public NativePasskeyChallengeResponse assertionOptions() {
        return this.passkeyService.createAuthenticationOptions();
    }

    @PostMapping("/passkeys/registration/options")
    @RateLimiter(name = "passkeyRegistration")
    public NativePasskeyChallengeResponse registrationOptions(Authentication authentication) {
        return this.passkeyService.createRegistrationOptions(authentication);
    }

    @PostMapping("/passkeys/registration")
    @RateLimiter(name = "passkeyRegistration")
    public ResponseEntity<Void> register(
            @Valid @RequestBody PasskeyRegistrationRequest request,
            Authentication authentication) {
        this.passkeyService.completeRegistration(
                request.challengeId(), request.credentialJson(), request.label(), authentication);
        return ResponseEntity.noContent().build();
    }

    public record PasskeyRegistrationRequest(
            @NotBlank @Size(max = 64) String challengeId,
            @NotBlank @Size(max = 32768) String credentialJson,
            @Size(max = 255) String label) {
    }
}
