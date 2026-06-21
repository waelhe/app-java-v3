package com.marketplace.shared.security.oauth2;

import com.marketplace.shared.api.OAuth2UserProvisioningPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.config.MarketplaceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock private OAuth2UserProvisioningPort provisioningPort;
    @Mock private JwtEncoder jwtEncoder;
    @Mock private UserLookupPort userLookupPort;
    @Mock private MarketplaceProperties properties;

    @InjectMocks private OAuth2LoginSuccessHandler handler;

    @Test
    void onAuthenticationSuccess_provisionsUserAndIssuesJwt() throws Exception {
        UUID userId = UUID.randomUUID();
        OAuth2AuthenticationToken authToken = mock(OAuth2AuthenticationToken.class);
        OAuth2User oAuth2User = mock(OAuth2User.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(authToken.getPrincipal()).thenReturn(oAuth2User);
        when(oAuth2User.getAttribute("sub")).thenReturn("12345");
        when(oAuth2User.getAttribute("email")).thenReturn("user@test.com");
        when(oAuth2User.getAttribute("name")).thenReturn("Test User");
        when(provisioningPort.provisionUser("google", "12345", "user@test.com", "Test User"))
                .thenReturn(userId);
        when(userLookupPort.findById(userId))
                .thenReturn(Optional.of(new UserSummary(userId, "user@test.com", "Test User", "CONSUMER", Instant.now(), Instant.now())));

        MarketplaceProperties.Security.AuthServer authServer = mock(MarketplaceProperties.Security.AuthServer.class);
        MarketplaceProperties.Security security = mock(MarketplaceProperties.Security.class);
        when(properties.security()).thenReturn(security);
        when(security.authServer()).thenReturn(authServer);
        when(authServer.issuer()).thenReturn("http://localhost:8080");

        Jwt mockJwt = Jwt.withTokenValue("jwt-token")
                .header("alg", "RS256")
                .claim("sub", "google:12345")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("http://localhost:8080")
                .build();
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt);

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        verify(provisioningPort).provisionUser("google", "12345", "user@test.com", "Test User");
        verify(jwtEncoder).encode(any());
        verify(response).sendRedirect(contains("/oauth2/redirect?token=jwt-token"));
    }
}
