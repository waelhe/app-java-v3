package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the current authenticated user from the JWT.
 *
 * <p>Handles two JWT issuance paths:
 * <ul>
 *   <li><b>Direct login</b> (TwoStepLoginService): {@code sub} = userId UUID,
 *       {@code userId} claim = userId UUID. Resolved via {@code findById(userId)}.</li>
 *   <li><b>OAuth2 social login</b> (OAuth2LoginSuccessHandler): {@code sub} = userId UUID,
 *       {@code userId} claim = userId UUID. Same resolution path.</li>
 *   <li><b>Legacy/password-grant JWTs</b> (if any): {@code sub} = email,
 *       no {@code userId} claim. Resolved via {@code findBySubject(sub)}.</li>
 * </ul>
 *
 * <p><b>Self-injection for @Cacheable</b>: {@code resolveUserIdFromDb} is called via
 * {@code self.resolveUserIdFromDb(userId)} to ensure the Spring AOP proxy intercepts
 * the call and the {@code @Cacheable} annotation is honored. Direct {@code this.}
 * invocation bypasses the proxy and the cache is never consulted.
 * Reference: Spring Framework Reference -- AOP Understanding AOP Proxies:
 * "Only external method calls coming in through the proxy are intercepted."
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security JWT Resource Server</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/UUID.html#fromString(java.lang.String)">UUID.fromString Javadoc</a>
 */
@Component
public class IdentityUserProvider implements CurrentUserProvider {

    private final UserRepository userRepository;

    /**
     * Self-reference for proxy-based @Cacheable to work on self-invocation.
     * The @Lazy annotation prevents circular dependency during bean creation.
     */
    private final IdentityUserProvider self;

    public IdentityUserProvider(UserRepository userRepository,
                                 @Lazy IdentityUserProvider self) {
        this.userRepository = userRepository;
        this.self = self;
    }

    @Override
    public UUID getCurrentUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            // Social-login and direct-login JWTs carry an explicit "userId" claim.
            Object userIdClaim = jwtToken.getToken().getClaim("userId");
            if (userIdClaim instanceof String userIdStr) {
                UUID parsedUserId = null;
                try {
                    parsedUserId = UUID.fromString(userIdStr);
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID format -- fall through to subject-based lookup.
                }

                if (parsedUserId != null) {
                    final UUID userId = parsedUserId;
                    // Call via self proxy so @Cacheable is honored.
                    return self.resolveUserIdFromDb(userId);
                }
            }

            // Fallback: JWTs without userId claim (legacy/password-grant) -- sub = email.
            String subject = jwtToken.getToken().getSubject();
            return userRepository.findBySubject(subject)
                    .map(User::getId)
                    .orElseThrow(() -> new com.marketplace.shared.api.ResourceNotFoundException(
                            "User not found for subject: " + subject));
        }
        throw new IllegalArgumentException("Unsupported authentication type");
    }

    /**
     * Cached lookup of user ID by UUID. Avoids a DB round-trip on every authenticated
     * API call. The cache TTL is set globally via {@code spring.cache.redis.time-to-live}
     * in application.yml (currently 30 minutes).
     *
     * <p>Must be called via the Spring proxy (use {@code self.resolveUserIdFromDb()})
     * for the {@code @Cacheable} annotation to take effect.
     *
     * @param userId the user UUID from the JWT claim
     * @return the same UUID (verified to exist in the DB)
     * @throws com.marketplace.shared.api.ResourceNotFoundException if the user was deleted
     */
    @Cacheable("userIdByJwt")
    public UUID resolveUserIdFromDb(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getId)
                .orElseThrow(() -> new com.marketplace.shared.api.ResourceNotFoundException(
                        "User not found for userId claim: " + userId));
    }

    @Override
    public boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
    }
}
