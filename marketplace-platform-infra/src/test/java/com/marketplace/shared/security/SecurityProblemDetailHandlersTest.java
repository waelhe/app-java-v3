package com.marketplace.shared.security;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3: the 401/403 problem-detail handlers must emit the RFC 6750 §3.1
 * {@code WWW-Authenticate} challenge so OAuth2/bearer clients can tell
 * an invalid token (401) from an insufficient scope (403).
 */
class SecurityProblemDetailHandlersTest {

    @Test
    void authenticationEntryPointChallengesWithInvalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityConfig(properties(), new ObjectMapper())
                .problemDetailAuthenticationEntryPoint()
                .commence(request, response, new AuthenticationCredentialsNotFoundException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("RFC 6750 challenge for a missing/invalid bearer token")
                .isEqualTo("Bearer realm=\"marketplace\", error=\"invalid_token\"");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

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
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", ""))
                )
        );
    }
}