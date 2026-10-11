package com.marketplace.notifications;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 7 (plan D-10, ADR-0003): the /me push-token surface — the device
 * registry's lifecycle (identity from the authentication itself, the /me
 * family shape; no token travels another user's path).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/me/push-tokens", version = "1.0")
public class PushTokenController {

    private final PushTokenService pushTokenService;

    public PushTokenController(PushTokenService pushTokenService) {
        this.pushTokenService = pushTokenService;
    }

    @PostMapping
    @Operation(summary = "Register (or rebind) this device's push token",
            description = "Idempotent by the token itself: the same token rebinds to the "
                    + "caller (the reinstall path). The push channel's addressing record.")
    public ResponseEntity<PushTokenResponse> register(
            @Valid @RequestBody RegisterPushTokenRequest request, Authentication authentication) {
        PushToken saved = pushTokenService.registerForCaller(
                request.token(), PushToken.Platform.valueOf(request.platform()), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(PushTokenResponse.of(saved));
    }

    @DeleteMapping
    @Operation(summary = "Unregister a push token", description = "Removes the caller's own "
            + "device token — a token is only ever removed by its owner.")
    public ResponseEntity<Void> unregister(
            @RequestParam @Schema(description = "The device token to remove") @NotBlank String token,
            Authentication authentication) {
        pushTokenService.unregisterForCaller(token, authentication);
        return ResponseEntity.noContent().build();
    }

    public record RegisterPushTokenRequest(
            @NotBlank @Size(max = 4096) String token,
            @NotNull @Schema(description = "The device platform", allowableValues = "ANDROID,IOS,WEB")
            String platform) {
    }

    public record PushTokenResponse(UUID id, UUID userId, String platform) {
        static PushTokenResponse of(PushToken token) {
            return new PushTokenResponse(token.getId(), token.getUserId(),
                    token.getPlatform().name());
        }
    }
}
