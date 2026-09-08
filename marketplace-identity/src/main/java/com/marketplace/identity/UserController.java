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

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
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
}
