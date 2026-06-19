package com.marketplace.shared.security.oauth2;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.OAuth2UserProvisioningPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.config.MarketplaceProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link OAuth2LoginSuccessHandler}.
 *
 * <p>Verifies the post-fix behavior:
 * <ul>
 *   <li>JWT is set as an HttpOnly + Secure + SameSite=Strict cookie, NOT in the URL</li>
 *   <li>{@code aud} claim is present (RFC 9068 §2.2)</li>
 *   <li>{@code sub} claim is the stable user UUID, not "provider:providerId"</li>
 *   <li>Unverified OAuth2 emails are refused (OIDC Core §5.1)</li>
 *   <li>Redirect URL contains NO token</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock private OAuth2UserProvisioningPort provisioningPort;
    @Mock private JwtEncoder jwtEncoder;
    @Mock private UserLookupPort userLookupPort;
    @Mock private MarketplaceProperties properties;

    @InjectMocks private OAuth2LoginSuccessHandler handler;

    private void stubProperties(String issuer, String audience) {
        MarketplaceProperties.Security.AuthServer authServer = mock(MarketplaceProperties.Security.AuthServer.class);
        MarketplaceProperties.Security security = mock(MarketplaceProperties.Security.class);
        MarketplaceProperties.Security.Jwt jwt = mock(MarketplaceProperties.Security.Jwt.class);
        when(properties.security()).thenReturn(security);
        when(security.authServer()).thenReturn(authServer);
        when(authServer.issuer()).thenReturn(issuer);
        when(security.jwt()).thenReturn(jwt);
        when(jwt.audience()).thenReturn(audience);
    }

    private Jwt mockJwt() {
        return Jwt.withTokenValue("jwt-token-value")
                .header("alg", "RS256")
                .claim("sub", "ignored")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("http://localhost:8080")
                .audience(java.util.List.of("marketplace-api"))
                .build();
    }

    @Test
    void onAuthenticationSuccess_provisionsUserAndSetsCookieWithoutJwtInUrl() throws Exception {
        UUID userId = UUID.randomUUID();
        OAuth2AuthenticationToken authToken = mock(OAuth2AuthenticationToken.class);
        OAuth2User oAuth2User = mock(OAuth2User.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(authToken.getPrincipal()).thenReturn(oAuth2User);
        when(oAuth2User.getAttribute("sub")).thenReturn("12345");
        when(oAuth2User.getAttribute("email")).thenReturn("user@test.com");
        when(oAuth2User.getAttribute("name")).thenReturn("Test User");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(true);
        when(provisioningPort.provisionUser("google", "12345", "user@test.com", "Test User"))
                .thenReturn(userId);
        when(userLookupPort.findById(userId))
                .thenReturn(Optional.of(new UserSummary(userId, "user@test.com", "Test User", "CONSUMER", Instant.now(), Instant.now())));
        stubProperties("http://localhost:8080", "marketplace-api");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt());

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        verify(provisioningPort).provisionUser("google", "12345", "user@test.com", "Test User");
        verify(jwtEncoder).encode(any());

        ArgumentCaptor<String> redirectCaptor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(redirectCaptor.capture());
        String redirectUrl = redirectCaptor.getValue();
        assertEquals("/oauth2/redirect", redirectUrl, "Redirect URL must NOT contain the token");
        assertFalse(redirectUrl.contains("token="), "No JWT in URL (RFC 6749 §10.6)");

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());
        Cookie cookie = cookieCaptor.getValue();
        assertEquals("session_token", cookie.getName());
        assertEquals("jwt-token-value", cookie.getValue());
        assertTrue(cookie.isHttpOnly(), "Cookie must be HttpOnly (OWASP)");
        assertTrue(cookie.getSecure(), "Cookie must be Secure (OWASP)");
        assertEquals("/", cookie.getPath());
        assertEquals(3600, cookie.getMaxAge());
        assertEquals("Strict", cookie.getAttribute("SameSite"));
    }

    @Test
    void onAuthenticationSuccess_jwtContainsAudClaim() throws Exception {
        UUID userId = UUID.randomUUID();
        OAuth2AuthenticationToken authToken = mock(OAuth2AuthenticationToken.class);
        OAuth2User oAuth2User = mock(OAuth2User.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(authToken.getPrincipal()).thenReturn(oAuth2User);
        when(oAuth2User.getAttribute("sub")).thenReturn("12345");
        when(oAuth2User.getAttribute("email")).thenReturn("user@test.com");
        when(oAuth2User.getAttribute("name")).thenReturn("Test User");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(true);
        when(provisioningPort.provisionUser(any(), any(), any(), any())).thenReturn(userId);
        when(userLookupPort.findById(userId))
                .thenReturn(Optional.of(new UserSummary(userId, "user@test.com", "Test User", "CONSUMER", Instant.now(), Instant.now())));
        stubProperties("http://localhost:8080", "marketplace-api");

        ArgumentCaptor<JwtEncoderParameters> paramsCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        when(jwtEncoder.encode(paramsCaptor.capture())).thenReturn(mockJwt());

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        JwtClaimsSet claims = paramsCaptor.getValue().getClaims();
        assertNotNull(claims.getAudience(), "JWT must have an `aud` claim (RFC 9068 §2.2)");
        assertTrue(claims.getAudience().contains("marketplace-api"));
        assertEquals(userId.toString(), claims.getSubject().toString(),
                "sub must be the stable user UUID (OIDC Core §5.7)");
    }

    @Test
    void onAuthenticationSuccess_refusesUnverifiedEmail() throws Exception {
        OAuth2AuthenticationToken authToken = mock(OAuth2AuthenticationToken.class);
        OAuth2User oAuth2User = mock(OAuth2User.class);

        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(authToken.getPrincipal()).thenReturn(oAuth2User);
        when(oAuth2User.getAttribute("sub")).thenReturn("12345");
        when(oAuth2User.getAttribute("email")).thenReturn("attacker@unverified.com");
        when(oAuth2User.getAttribute("name")).thenReturn("Attacker");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(false);

        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
                () -> handler.onAuthenticationSuccess(mock(HttpServletRequest.class), mock(HttpServletResponse.class), authToken),
                "OAuth2 login with unverified email must be refused (OIDC Core §5.1)");

        verifyNoInteractions(provisioningPort);
        verifyNoInteractions(jwtEncoder);
    }

    @Test
    void onAuthenticationSuccess_refusesWhenUserNotFoundInDb() throws Exception {
        UUID userId = UUID.randomUUID();
        OAuth2AuthenticationToken authToken = mock(OAuth2AuthenticationToken.class);
        OAuth2User oAuth2User = mock(OAuth2User.class);

        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(authToken.getPrincipal()).thenReturn(oAuth2User);
        when(oAuth2User.getAttribute("sub")).thenReturn("12345");
        when(oAuth2User.getAttribute("email")).thenReturn("user@test.com");
        when(oAuth2User.getAttribute("name")).thenReturn("Test User");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(true);
        when(provisioningPort.provisionUser(any(), any(), any(), any())).thenReturn(userId);
        when(userLookupPort.findById(userId)).thenReturn(Optional.empty());

        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
                () -> handler.onAuthenticationSuccess(mock(HttpServletRequest.class), mock(HttpServletResponse.class), authToken),
                "OAuth2 login must fail when user provisioning did not produce a DB row");

        verifyNoInteractions(jwtEncoder);
    }
}
