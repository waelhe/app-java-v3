package com.marketplace.shared.security.oauth2;

import com.marketplace.shared.api.OAuth2UserProvisioningPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.config.MarketplaceProperties;
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
 * <p>Flow:
 * <ol>
 *   <li>Extracts user info from OAuth2 provider</li>
 *   <li>Provisions user in local DB via {@link OAuth2UserProvisioningPort}</li>
 *   <li>Issues a JWT using the same {@link JwtEncoder} as Spring Authorization Server</li>
 *   <li>Redirects to frontend with the JWT as a query parameter</li>
 * </ol>
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html">Spring Security OAuth2 Login Advanced</a>
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);
    private static final String FRONTEND_REDIRECT_URL = "/oauth2/redirect?token=";

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

        log.info("OAuth2 login success: provider={}, providerId={}, email={}", provider, providerId, email);

        UUID userId = provisioningPort.provisionUser(provider, providerId, email, name);

        // Roles from DB via UserLookupPort
        var userOpt = userLookupPort.findById(userId);
        List<String> roles = userOpt
                .map(u -> List.of(u.role()))
                .orElse(List.of("CONSUMER"));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(provider + ":" + providerId)
                .claim("userId", userId.toString())
                .claim("email", email)
                .claim("name", name)
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .issuer(properties.security().authServer().issuer())
                .build();

        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        response.sendRedirect(FRONTEND_REDIRECT_URL + jwt);
    }
}
