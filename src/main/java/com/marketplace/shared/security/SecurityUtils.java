package com.marketplace.shared.security;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Helper component that resolves the internal user ID from a JWT authentication token.
 * JWT contains OIDC subject (e.g., "auth0|abc123"), which maps to the internal UUID
 * via the {@link User#getSubject()} field.
 */
@Component
public class SecurityUtils {

    private final UserRepository userRepository;

    public SecurityUtils(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Resolves the internal user UUID from the JWT subject claim.
     *
     * @param authentication the JWT authentication token
     * @return the internal user UUID
     * @throws IllegalArgumentException if the user is not found
     */
    public UUID getCurrentUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            String subject = jwtToken.getToken().getSubject();
            return userRepository.findBySubject(subject)
                    .map(User::getId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found for subject: " + subject));
        }
        throw new IllegalArgumentException("Unsupported authentication type");
    }

    /**
     * Checks if the current user has the ADMIN role.
     */
    public boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
    }
}