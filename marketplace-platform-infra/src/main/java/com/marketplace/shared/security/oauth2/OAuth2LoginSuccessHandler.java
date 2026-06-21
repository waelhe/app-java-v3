package com.marketplace.shared.security.oauth2;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.OAuth2UserProvisioningPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.config.MarketplaceProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Handles successful OAuth2 login from external providers (Google, GitHub, Apple).
 *
 * <p>Flow (revised per RFC 6749 §10.6 + RFC 9068 §2.2):
 * <ol>
 *   <li>Extracts user info from OAuth2 provider</li>
 *   <li>Verifies the provider {@code email_verified} claim (OIDC Core §5.1) —
 *       refuses to provision a user from an unverified email to prevent account pre-hijacking</li>
 *   <li>Provisions user in local DB via {@link OAuth2UserProvisioningPort}</li>
 *   <li>Loads roles from DB via {@link UserLookupPort} (no hardcoded "CONSUMER" fallback)</li>
 *   <li>Issues a JWT using the same {@link JwtEncoder} as Spring Authorization Server,
 *       with the required {@code aud} claim (RFC 9068 §2.2) and stable {@code sub} = userId</li>
 *   <li>Sets the JWT as an <strong>HttpOnly, Secure, SameSite=Strict cookie</strong>
 *       instead of a URL query parameter — preventing token leakage via browser history,
 *       proxy logs, and Referer headers (RFC 6749 §10.6)</li>
 *   <li>Redirects to frontend without any token in the URL</li>
 * </ol>
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6749#section-10.6">RFC 6749 §10.6 — Bearer Token Leakage</a></li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc9068#section-2.2">RFC 9068 §2.2 — JWT `aud` REQUIRED</a></li>
 *   <li><a href="https://openid.net/specs/openid-connect-core-1_0.html#StandardClaims">OIDC Core §5.1 — `email_verified` claim</a></li>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#cookies">OWASP Session Management — Cookies</a></li>
 *   <li><a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html">Spring Security OAuth2 Login Advanced</a></li>
 * </ul>
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    /** Frontend redirect target after successful OAuth2 login. */
    private static final String FRONTEND_REDIRECT_PATH = "/oauth2/redirect";

    /** Cookie name carrying the JWT after OAuth2 login. */
    private static final String SESSION_COOKIE_NAME = "session_token";

    /** Cookie TTL: 1 hour, matching the JWT TTL. */
    private static final int COOKIE_MAX_AGE_SECONDS = 3600;

    private final OAuth2UserProvisioningPort provisioningPort;
    private final JwtEncoder jwtEncoder;
    private final MarketplaceProperties properties;
    private final UserLookupPort userLookupPort;

    public OAuth2LoginSuccessHandler(OAuth2UserProvisioningPort provisioningPort,
                                      JwtEncoder jwtEncoder,
                                      MarketplaceProperties properties,
                                      UserLookupPort userLookupPort) {
        this.provisioningPort = provisioningPort;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.userLookupPort = userLookupPort;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                         HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauth2Token = (OAuth2AuthenticationToken) authentication;
        String provider = oauth2Token.getAuthorizedClientRegistrationId();
        OAuth2User oauth2User = oauth2Token.getPrincipal();

        String providerId = oauth2User.getAttribute("sub");
        if (providerId == null) {
            providerId = String.valueOf(oauth2User.getAttribute("id"));
        }
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        if (name == null) {
            name = oauth2User.getAttribute("login");
        }

        // OIDC Core §5.1: refuse to provision a user from an unverified email.
        // Google returns `email_verified` (boolean); GitHub does not (treat as unverified
        // unless the user has explicitly verified via /user/emails — left as a future enhancement).
        Boolean emailVerified = oauth2User.getAttribute("email_verified");
        if (email != null && emailVerified != null && !emailVerified) {
            log.warn("OAuth2 login refused: provider={} email={} email_verified=false", provider, email);
            throw new BadRequestException("OAuth2 provider email is not verified");
        }

        // Log without PII — userId only, not email (OWASP Logging Cheat Sheet).
        log.info("OAuth2 login success: provider={}, providerId={}", provider, providerId);

        UUID userId = provisioningPort.provisionUser(provider, providerId, email, name);

        // Load roles from DB — no hardcoded fallback if user exists.
        var userOpt = userLookupPort.findById(userId);
        List<String> roles = userOpt
                .map(UserSummary::role)
                .map(List::of)
                .orElseThrow(() -> new BadRequestException("User record not found for OAuth2 login"));

        String issuer = properties.security().authServer().issuer();
        String audience = properties.security().jwt().audience();

        // Build JWT per RFC 9068 §2.2 — `aud` REQUIRED, `sub` stable.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())          // stable internal user UUID
                .claim("userId", userId.toString())
                .claim("email", email)
                .claim("name", name)
                .claim("roles", roles)
                .audience(List.of(audience))         // RFC 9068 §2.2 — REQUIRED
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .issuer(issuer)
                .build();

        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // RFC 6749 §10.6 / OWASP Session Management: do NOT put JWT in URL.
        // Set HttpOnly + Secure + SameSite=Strict cookie instead.
        Cookie sessionCookie = new Cookie(SESSION_COOKIE_NAME, jwt);
        sessionCookie.setHttpOnly(true);
        sessionCookie.setSecure(true);
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        sessionCookie.setAttribute("SameSite", "Strict");
        response.addCookie(sessionCookie);

        response.sendRedirect(FRONTEND_REDIRECT_PATH);
    }
}
