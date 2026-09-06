package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level round-trip of the roles authorization contract between the two
 * authorization beans declared by {@link SecurityConfig}:
 *
 * <ol>
 *   <li><b>write side</b> &mdash; {@code jwtTokenCustomizer()} flattens the principal's
 *       authorities into a bare {@code roles} claim ("ROLE_ADMIN" &rarr; "ADMIN") per
 *       the official how-to
 *       <a href="https://docs.spring.io/spring-authorization-server/reference/guides/how-to-custom-claims-authorities.html">
 *       Customize JWT Claims: authorities</a> (token-side behavior is covered in
 *       detail by {@link OAuth2TokenCustomizerTest});</li>
 *   <li><b>read side</b> &mdash; {@code jwtAuthenticationConverter()} maps that same
 *       {@code roles} claim back to {@code ROLE_}-prefixed authorities via
 *       {@link org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter#setAuthorityPrefix};
 *       the two sides are complementary, not inverse: the prefix stripped on mint
 *       is re-added on decode, exactly once;</li>
 *   <li><b>gate</b> &mdash; the resulting authorities must satisfy
 *       {@code hasRole("ADMIN")}, the rule guarding {@code /api/v1/admin/**} in
 *       {@code resourceServerSecurityFilterChain} (SecurityConfig.java:131),
 *       evaluated here with the framework's own
 *       {@link AuthorityAuthorizationManager#hasRole}.</li>
 * </ol>
 *
 * <p>This pins the bean contract inside the module that declares the beans. It
 * complements the end-to-end HTTP gate {@code AuthorizationServerLoginGateIntegrationTest}
 * in {@code marketplace-app}, which proves the same contract over the wire
 * (mint &rarr; decode &rarr; authorize) through the real {@code JwtDecoder}. The
 * {@code roles} claim used on both sides is registered by RFC 9068 &sect;7.2.1.1.
 */
class JwtRolesRoundTripTest {

    private static final String AUDIENCE = "marketplace-api";

    private final SecurityConfig securityConfig = new SecurityConfig(properties(), null);

    @Test
    void rolesClaimRoundTripsToRoleAuthoritiesAndSatisfiesTheAdminGate() {
        Jwt mintedToken = mintedAccessToken(AuthorityUtils.createAuthorityList("ROLE_ADMIN", "ROLE_USER"));

        // write side: bare roles, no ROLE_ prefix
        Collection<?> rolesClaim = (Collection<?>) mintedToken.getClaims().get("roles");
        List<String> bareRoles = rolesClaim.stream().map(String::valueOf).toList();
        assertThat(bareRoles).containsExactlyInAnyOrder("ADMIN", "USER");

        // read side: the prefix is re-added exactly once - no loss, no bare
        // authority, no ROLE_ROLE_ double prefix. JwtAuthenticationConverter
        // (Spring Security 7.1.1) additionally contributes its own
        // FactorGrantedAuthority.BEARER_AUTHORITY to every JWT authentication
        // (factor-aware authorization); the full 7.1.1 contract is pinned here.
        AbstractAuthenticationToken authentication = securityConfig.jwtAuthenticationConverter().convert(mintedToken);
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertThat(authorities).containsExactlyInAnyOrder(
                FactorGrantedAuthority.BEARER_AUTHORITY, "ROLE_ADMIN", "ROLE_USER");

        // gate: hasRole("ADMIN") - the rule on /api/v1/admin/**
        assertThat(AuthorityAuthorizationManager.<Object>hasRole("ADMIN")
                .authorize(() -> authentication, null).isGranted()).isTrue();
    }

    @Test
    void nonAdminPrincipalIsDeniedByTheAdminRoleGate() {
        Jwt mintedToken = mintedAccessToken(AuthorityUtils.createAuthorityList("ROLE_USER"));

        AbstractAuthenticationToken authentication = securityConfig.jwtAuthenticationConverter().convert(mintedToken);

        // mirrors the 403 negative path of the end-to-end login gate
        assertThat(AuthorityAuthorizationManager.<Object>hasRole("ADMIN")
                .authorize(() -> authentication, null).isGranted()).isFalse();
        assertThat(AuthorityAuthorizationManager.<Object>hasRole("USER")
                .authorize(() -> authentication, null).isGranted()).isTrue();
    }

    /**
     * Mints the access-token claims exactly the way the authorization server
     * does: the real {@code jwtTokenCustomizer()} bean applied to the
     * principal's authorities, bridged into the {@code Jwt} that the
     * resource-server side sees after decoding.
     */
    private Jwt mintedAccessToken(List<? extends GrantedAuthority> authorities) {
        RegisteredClient registeredClient = RegisteredClient.withId("test-client")
                .clientId("test-client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("read")
                .build();

        OAuth2TokenCustomizer<JwtEncodingContext> customizer = securityConfig.jwtTokenCustomizer();

        JwtEncodingContext context = JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("user-123"))
                .registeredClient(registeredClient)
                .principal(new UsernamePasswordAuthenticationToken("user", null, authorities))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();

        customizer.customize(context);

        Map<String, Object> claims = context.getClaims().build().getClaims();
        return Jwt.withTokenValue("round-trip-access-token")
                .header("alg", "RS256")
                .claims(claimsBuilder -> claimsBuilder.putAll(claims))
                .build();
    }

    private static MarketplaceProperties properties() {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                AUDIENCE
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", ""))
                )
        );
    }
}
