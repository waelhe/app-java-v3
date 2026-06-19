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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles successful OAuth2 login from external providers (Google, GitHub, Apple).
 *
 * <p>Flow (revised per RFC 6749 section10.6 + RFC 9068 section2.2):
 * <ol>
 *   <li>Extracts user info from OAuth2 provider</li>
 *   <li>Verifies the provider {@code email_verified} claim (OIDC Core section5.1) --
 *       refuses to provision a user from an unverified email to prevent account pre-hijacking</li>
 *   <li>Provisions user in local DB via {@link OAuth2UserProvisioningPort}</li>
 *   <li>Loads roles from DB via {@link UserLookupPort} (no hardcoded "CONSUMER" fallback)</li>
 *   <li>Issues a JWT using the same {@link JwtEncoder} as Spring Authorization Server,
 *       with the required {@code aud} claim (RFC 9068 section2.2) and stable {@code sub} = userId</li>
 *   <li>Sets the JWT as an <strong>HttpOnly, Secure, SameSite=Strict cookie</strong>
 *       instead of a URL query parameter -- preventing token leakage via browser history,
 *       proxy logs, and Referer headers (RFC 6749 section10.6)</li>
 *   <li>Redirects to frontend without any token in the URL</li>
 * </ol>
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6749#section-10.6">RFC 6749 section10.6 -- Bearer Token Leakage</a></li>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc9068#section-2.2">RFC 9068 section2.2 -- JWT `aud` REQUIRED</a></li>
 *   <li><a href="https://openid.net/specs/openid-connect-core-1_0.html#StandardClaims">OIDC Core section5.1 -- `email_verified` claim</a></li>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#cookies">OWASP Session Management -- Cookies</a></li>
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
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final RestClient restClient;

    public OAuth2LoginSuccessHandler(OAuth2UserProvisioningPort provisioningPort,
                                      JwtEncoder jwtEncoder,
                                      MarketplaceProperties properties,
                                      UserLookupPort userLookupPort,
                                      OAuth2AuthorizedClientRepository authorizedClientRepository,
                                      RestClient.Builder restClientBuilder) {
        this.provisioningPort = provisioningPort;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.userLookupPort = userLookupPort;
        this.authorizedClientRepository = authorizedClientRepository;
        this.restClient = restClientBuilder.build();
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

        // OIDC Core section5.1 + OWASP Account Provisioning: refuse to provision a user
        // from an unverified email to prevent account pre-hijacking.
        //
        // Google returns `email_verified` (boolean) in the userinfo response.
        // GitHub does NOT return `email_verified` -- the claim is null. For GitHub,
        // we call the /user/emails API (requires `user:email` scope, already configured)
        // to check if the primary email is verified.
        //
        // Reference: OIDC Core section5.1 -- email_verified claim;
        // https://docs.github.com/en/rest/users/emails -- "List email addresses for the
        // authenticated user" returns [{email, primary, verified, visibility}].
        Boolean emailVerified = oauth2User.getAttribute("email_verified");
        if (emailVerified == null) {
            // Provider doesn't return email_verified (GitHub) -- verify via /user/emails API.
            emailVerified = verifyEmailViaProviderApi(oauth2Token, request, email);
        }
        if (email == null || !emailVerified) {
            // Refuse provisioning if email is null (no verifiable contact) OR if
            // email_verified is false. OIDC Core section5.1 + OWASP Account Provisioning:
            // "Verify the user's identity before provisioning."
            log.warn("OAuth2 login refused: provider={} email={} emailVerified={}", provider, email, emailVerified);
            // Throw OAuth2AuthenticationException (not BadRequestException) so Spring Security's
            // AuthenticationFailureHandler processes it correctly -- redirects to failure URL
            // instead of propagating to GlobalExceptionHandler as HTTP 500.
            // Reference: Spring Security OAuth2 Login -- AuthenticationFailureHandler handles
            // AuthenticationException subclasses.
            throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                    new org.springframework.security.oauth2.core.OAuth2Error("email_not_verified",
                            "OAuth2 provider email is not verified", null));
        }

        // Log without PII -- userId only, not email (OWASP Logging Cheat Sheet).
        log.info("OAuth2 login success: provider={}, providerId={}", provider, providerId);

        UUID userId = provisioningPort.provisionUser(provider, providerId, email, name);

        // Load roles from DB -- no hardcoded fallback if user exists.
        var userOpt = userLookupPort.findById(userId);
        List<String> roles = userOpt
                .map(UserSummary::role)
                .map(List::of)
                .orElseThrow(() -> new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                        new org.springframework.security.oauth2.core.OAuth2Error("user_not_found",
                                "User record not found for OAuth2 login", null)));

        String issuer = properties.security().authServer().issuer();
        String audience = properties.security().jwt().audience();

        // Build JWT per RFC 9068 section2.2 -- `aud` REQUIRED, `sub` stable.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())          // stable internal user UUID
                .claim("userId", userId.toString())
                .claim("email", email)
                .claim("name", name)
                .claim("roles", roles)
                .audience(List.of(audience))         // RFC 9068 section2.2 -- REQUIRED
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .issuer(issuer)
                .build();

        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // RFC 6749 section10.6 / OWASP Session Management: do NOT put JWT in URL.
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

    /**
     * Verifies the user's primary email via the provider's email API.
     *
     * <p>Used for providers that don't return {@code email_verified} in the userinfo
     * response (e.g., GitHub). Calls {@code GET /user/emails} with the OAuth2 access
     * token and checks if the primary email is verified.
     *
     * <p><b>Reference</b>: GitHub REST API -- "List email addresses for the authenticated
     * user" returns {@code [{email, primary, verified, visibility}]}. Requires
     * {@code user:email} scope (already configured in application.yml).
     * <a href="https://docs.github.com/en/rest/users/emails">GitHub Emails API</a>
     *
     * <p><b>Type-safe deserialization</b>: uses Spring's {@link ParameterizedTypeReference}
     * to deserialize the response into a type-safe {@code List<Map<String, Object>>}.
     * This is the documented Spring RestClient pattern for generic response types and avoids
     * the unchecked-conversion warning that {@code .body(List.class)} (raw) would produce.
     * Reference: Spring Framework Reference -> Integration -> RestClient ->
     * "Type Information" (ParameterizedTypeReference).
     *
     * @param oauth2Token the OAuth2 authentication token
     * @param request the HTTP request (needed to load the authorized client)
     * @param email the email to verify
     * @return true if the email is verified, false otherwise (fail-closed on API errors)
     */
    private boolean verifyEmailViaProviderApi(OAuth2AuthenticationToken oauth2Token,
                                               HttpServletRequest request,
                                               String email) {
        try {
            OAuth2AuthorizedClient authorizedClient = authorizedClientRepository.loadAuthorizedClient(
                    oauth2Token.getAuthorizedClientRegistrationId(),
                    oauth2Token,
                    request);
            if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                log.warn("Cannot verify email: no OAuth2 access token for provider={}",
                        oauth2Token.getAuthorizedClientRegistrationId());
                return false; // fail-closed
            }

            String accessToken = authorizedClient.getAccessToken().getTokenValue();
            String provider = oauth2Token.getAuthorizedClientRegistrationId();

            // GitHub API: GET https://api.github.com/user/emails
            // Returns: [{"email":"...","primary":true,"verified":true,"visibility":"public"}]
            String apiUrl = "github".equals(provider)
                    ? "https://api.github.com/user/emails"
                    : null; // Future: add other providers here

            if (apiUrl == null) {
                log.warn("Email verification via API not supported for provider={}", provider);
                return false; // fail-closed for unknown providers
            }

            // Type-safe deserialization via ParameterizedTypeReference (Spring documented pattern
            // for generic response types). Avoids the raw List.class unchecked-conversion warning.
            List<Map<String, Object>> emails = restClient.get()
                    .uri(apiUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (emails == null) {
                return false;
            }

            // Find the primary email and check if it's verified.
            for (Map<String, Object> entry : emails) {
                Boolean primary = (Boolean) entry.get("primary");
                Boolean verified = (Boolean) entry.get("verified");
                String entryEmail = (String) entry.get("email");
                if (Boolean.TRUE.equals(primary) && entryEmail != null && entryEmail.equalsIgnoreCase(email)) {
                    return Boolean.TRUE.equals(verified);
                }
            }

            // Primary email not found or doesn't match -- fail-closed.
            return false;
        } catch (org.springframework.web.client.RestClientException e) {
            // Catch only RestClientException (network/HTTP errors -- connection refused,
            // timeout, 4xx/5xx from GitHub). Programming errors (ClassCastException, NPE)
            // propagate so they're visible in logs and monitoring -- not masked as "API errors".
            // Reference: Spring Web -- RestClientException extends NestedRuntimeException;
            // ResourceAccessException (network I/O) extends RestClientException.
            log.warn("Failed to verify email via provider API -- failing closed", e);
            return false;
        }
    }
}
