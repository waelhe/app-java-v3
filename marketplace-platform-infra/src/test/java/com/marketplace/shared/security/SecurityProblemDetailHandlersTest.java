package com.marketplace.shared.security;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3: the 401/403 problem-detail handlers must emit the RFC 6750 §3.1
 * {@code WWW-Authenticate} challenge so OAuth2/bearer clients can tell
 * an invalid token (401) from an insufficient scope (403).
 *
 * <p>Challenge semantics verified against the official sources: RFC 6750
 * §3 ("If the protected resource request included an access token and failed
 * authentication, the resource server SHOULD include the 'error' attribute")
 * and §3.1 ("If the request lacks any authentication information ... the
 * resource server SHOULD NOT include an error code or other error
 * information"), plus the framework's own
 * {@code BearerTokenAuthenticationEntryPoint} (Spring Security 7), which
 * derives the error attributes from the {@code OAuth2AuthenticationException}
 * carried by an invalid supplied token.
 */
class SecurityProblemDetailHandlersTest {

    /**
     * A request that carried no credentials reaches the entry point as an
     * {@link InsufficientAuthenticationException} (the
     * {@code ExceptionTranslationFilter} conversion for an anonymous access
     * denial) and must be challenged with the bare realm challenge — no
     * {@code error} param — per RFC 6750 §3.1.
     */
    @Test
    void authenticationEntryPointChallengesWithoutErrorParamWhenRequestLacksCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityConfig(properties(), new ObjectMapper())
                .problemDetailAuthenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("Full authentication is required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("RFC 6750 §3.1 bare challenge for a request lacking credentials")
                .isEqualTo("Bearer realm=\"marketplace\"");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

    /**
     * A supplied bearer token that failed validation reaches the entry point
     * as an {@link InvalidBearerTokenException} (an
     * {@code OAuth2AuthenticationException} wrapping
     * {@code BearerTokenErrors.invalidToken}, thrown by
     * {@code JwtAuthenticationProvider} for a {@code BadJwtException}) and
     * must be challenged with {@code error="invalid_token"} per RFC 6750 §3.
     */
    @Test
    void authenticationEntryPointChallengesWithInvalidTokenWhenSuppliedTokenFailed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityConfig(properties(), new ObjectMapper())
                .problemDetailAuthenticationEntryPoint()
                .commence(request, response, new InvalidBearerTokenException("The access token expired"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("RFC 6750 §3 error attributes for a failed supplied bearer token")
                .startsWith("Bearer realm=\"marketplace\"")
                .contains("error=\"invalid_token\"")
                .contains("error_description=\"The access token expired\"");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

    /**
     * An authenticated request that lacks sufficient privileges (403) must be
     * challenged with {@code error="insufficient_scope"} per RFC 6750 §3.1
     * while still writing the problem+json body.
     */
    @Test
    void accessDeniedHandlerChallengesWithInsufficientScope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/system");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityConfig(properties(), new ObjectMapper())
                .problemDetailAccessDeniedHandler()
                .handle(request, response, new AccessDeniedException("insufficient scope"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("RFC 6750 challenge for a scope that is insufficient")
                .isEqualTo("Bearer realm=\"marketplace\", error=\"insufficient_scope\"");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

    /**
     * Builds the properties record the {@link SecurityConfig} beans under test
     * require, with the minimal non-null branches only.
     */
    private static MarketplaceProperties properties() {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                "marketplace-api"
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", "")),
                        new MarketplaceProperties.Security.Pseudonymization(""))
        );
    }
}
