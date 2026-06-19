package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the current authenticated user from the JWT.
 *
 * <p>Handles two JWT issuance paths:
 * <ul>
 *   <li><b>Password grant</b> (Spring Authorization Server): {@code sub} = email,
 *       no {@code userId} claim. Resolved via {@code findBySubject(sub)}.</li>
 *   <li><b>OAuth2 social login</b> (OAuth2LoginSuccessHandler): {@code sub} = userId UUID,
 *       {@code userId} claim = userId UUID. Resolved via {@code findById(userId)}.</li>
 * </ul>
 *
 * <p>The {@code userId} claim is preferred when present (social-login path) because it
 * avoids a DB lookup by subject (which would fail — the social-login user's subject is
 * "provider:providerId", not the UUID in {@code sub}). For password-grant JWTs without
 * the {@code userId} claim, the fallback to {@code findBySubject(sub)} handles the
 * email-as-subject case.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security JWT Resource Server</a>
 */
@Component
public class IdentityUserProvider implements CurrentUserProvider {

    private final UserRepository userRepository;

    public IdentityUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UUID getCurrentUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            // Social-login JWTs carry an explicit "userId" claim — use it directly.
            Object userIdClaim = jwtToken.getToken().getClaim("userId");
            if (userIdClaim instanceof String userIdStr) {
                try {
                    UUID userId = UUID.fromString(userIdStr);
                    return userRepository.findById(userId)
                            .map(User::getId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "User not found for userId claim: " + userId));
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID — fall through to subject-based lookup.
                }
            }

            // Password-grant JWTs: sub = email, no userId claim.
            String subject = jwtToken.getToken().getSubject();
            return userRepository.findBySubject(subject)
                    .map(User::getId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found for subject: " + subject));
        }
        throw new IllegalArgumentException("Unsupported authentication type");
    }

    @Override
    public boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
    }
}
