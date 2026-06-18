package com.marketplace.identity;

import com.marketplace.identity.dto.ChangePasswordRequest;
import com.marketplace.identity.dto.UpdateProfileRequest;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for user profile management.
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Getting current user info</li>
 *   <li>Updating profile (email, display name)</li>
 *   <li>Changing password</li>
 * </ul>
 */
@RestController
@RequestMapping(value = ApiConstants.IDENTITY, version = "1.0")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final CurrentUserProvider currentUserProvider;
    private final UserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService,
                           UserMapper userMapper,
                           CurrentUserProvider currentUserProvider,
                           UserDetailsManager userDetailsManager,
                           PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.currentUserProvider = currentUserProvider;
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal JwtAuthenticationToken token) {
        User user = userService.syncFromOidc(token);
        return ResponseEntity.ok(userMapper.toResponse(user));
    }

    /**
     * Updates the current user's profile (email and display name).
     */
    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @CacheEvict(cacheNames = {"users", "userSubjects"}, allEntries = true)
    public ResponseEntity<UserResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal JwtAuthenticationToken token) {
        java.util.UUID userId = currentUserProvider.getCurrentUserId(token);
        User updated = userService.updateProfile(userId, request.email(), request.displayName());
        return ResponseEntity.ok(userMapper.toResponse(updated));
    }

    /**
     * Changes the current user's password.
     * Requires the old password for verification.
     */
    @PutMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal JwtAuthenticationToken token) {
        java.util.UUID userId = currentUserProvider.getCurrentUserId(token);
        User user = userService.getById(userId);

        UserDetails userDetails = userDetailsManager.loadUserByUsername(user.getEmail());
        if (!passwordEncoder.matches(request.oldPassword(), userDetails.getPassword())) {
            throw new BadRequestException("Current password is incorrect");
        }

        PasswordValidator.validate(request.newPassword());

        String newEncodedPassword = passwordEncoder.encode(request.newPassword());
        org.springframework.security.core.userdetails.UserDetails updatedUser =
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(newEncodedPassword)
                        .roles(user.getRole().name().replace("ROLE_", ""))
                        .disabled(!userDetails.isEnabled())
                        .build();
        userDetailsManager.updateUser(updatedUser);

        return ResponseEntity.noContent().build();
    }
}
