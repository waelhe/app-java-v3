package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.cache.annotation.Cacheable;
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
 * <p>The {@code userId} claim is preferred when present because it avoids a DB lookup
 * by subject. For JWTs without the {@code userId} claim, the fallback to
 * {@code findBySubject(sub)} handles the email-as-subject case.
 *
 * <p><b>Exception handling</b>: {@code UUID.fromString()} throws
 * {@code IllegalArgumentException} for malformed strings (format error). The user
 * lookup uses {@code orElseThrow} which also throws — but a <em>different</em>
 * exception type ({@code ResourceNotFoundException}) so the format-error catch
 * does NOT accidentally swallow the lookup failure. See {@link UUID#fromString}
 * Javadoc: "Throws: IllegalArgumentException - If name does not conform to the
 * string representation."
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security JWT Resource Server</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/UUID.html#fromString(java.lang.String)">UUID.fromString Javadoc</a>
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
            // Social-login and direct-login JWTs carry an explicit "userId" claim.
            Object userIdClaim = jwtToken.getToken().getClaim("userId");
            if (userIdClaim instanceof String userIdStr) {
                // Parse the UUID — catch ONLY the format error (IllegalArgumentException
                // from UUID.fromString). The lookup error below uses a different exception
                // type so it is NOT caught here.
                UUID parsedUserId = null;
                try {
                    parsedUserId = UUID.fromString(userIdStr);
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID format — fall through to subject-based lookup.
                    // This is expected for legacy JWTs that don't have a userId claim
                    // but have a non-UUID subject.
                }

                if (parsedUserId != null) {
                    final UUID userId = parsedUserId;
                    // Delegate to cached lookup — avoids a DB round-trip on every API call.
                    // The JWT was already signature/issuer/audience/expiry-validated by Spring Security;
                    // the only purpose of this lookup is to catch the rare "user deleted after JWT issuance"
                    // case. A 30-minute cache TTL (configured in application.yml) is sufficient.
                    // Reference: Spring Boot Reference — @Cacheable: "demonstrates the use of caching
                    // on a potentially costly operation."
                    return resolveUserIdFromDb(userId);
                }
            }

            // Fallback: JWTs without userId claim (legacy/password-grant) — sub = email.
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
     * <p>If the user was deleted from the DB, {@code ResourceNotFoundException} propagates
     * (NOT cached — exceptions are not cached by default in Spring Cache).
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
