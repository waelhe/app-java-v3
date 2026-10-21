package com.marketplace.app.graphql;

import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserAccountView;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * platform's OWN password grant behind the Saleor {@code tokenCreate}/
 * {@code tokenRefresh}/{@code tokenVerify} mutations — the dashboard's
 * native sign-in, implemented over the platform's real primitives with
 * ZERO second issuer:
 *
 * <ul>
 *   <li><b>Credential verification:</b> the global {@link AuthenticationManager}
 *       (the framework-assembled DaoAuthenticationProvider the form-login
 *       chain itself uses — every account-state flag honored: disabled,
 *       locked and expired accounts all refuse exactly as the login gate
 *       does).</li>
 *   <li><b>Access-token minting:</b> the OFFICIAL {@link NimbusJwtEncoder}
 *       over the SAME {@code JWKSource} bean the authorization server signs
 *       with, carrying the SAME claim contract the {@code jwtTokenCustomizer}
 *       mints for OIDC access tokens (sub = login username, roles with the
 *       ROLE_ prefix stripped, the platform audience, the issuer from the
 *       authorization server's own settings) — the resource server's
 *       decoder validates them without any special case.</li>
 *   <li><b>Refresh tokens + persistence:</b> opaque cryptographically
 *       random strings persisted (with the access token and the roles
 *       attribute) as {@code oauth2_authorization} rows through the
 *       authorization server's own {@link OAuth2AuthorizationService} —
 *       so {@code tokenRefresh} rotates through the official
 *       {@code findByToken} API and {@code tokensDeactivateAll} (the
 *       identity owner's JDBC kill by principal) revokes them exactly
 *       like any OIDC-issued session.</li>
 *   <li><b>Verification:</b> the resource server's own {@link JwtDecoder}
 *       — the same validator every API request runs.</li>
 * </ul>
 *
 * <p>The client of record for the grant's authorization rows is the
 * platform's existing public (PKCE) client — the dashboard's own client —
 * resolved through the {@code RegisteredClientRepository} (the grant rides
 * the client registry's own row, never an invented client).</p>
 */
@Component
public class PlatformTokenGrantService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTokenGrantService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** The platform's standing access-token TTL (the L23-documented 900s). */
    static final Duration ACCESS_TOKEN_TTL = Duration.ofSeconds(900);

    /** The refresh-token TTL — 14 days, the platform's refresh posture. */
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    /** The roles attribute's key on the authorization rows. */
    private static final String ROLES_ATTRIBUTE = "platform.roles";

    // The #392 module-slice shape (the UserService/PasswordResetService
    // precedent verbatim): the security-infrastructure beans live outside
    // the GraphQL adapter slice — resolved lazily so every package-slice
    // test loads the adapter without the full authorization-server stack,
    // and the full application (and every integration test) resolves them.
    private final ObjectProvider<AuthenticationConfiguration> authenticationConfiguration;
    private final ObjectProvider<OAuth2AuthorizationService> authorizationService;
    private final ObjectProvider<RegisteredClientRepository> registeredClientRepository;
    private final ObjectProvider<JWKSource<SecurityContext>> jwkSource;
    private final ObjectProvider<AuthorizationServerSettings> authorizationServerSettings;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final IdentitySpi identitySpi;
    private final String audience;
    private final String publicClientId;

    public PlatformTokenGrantService(
            ObjectProvider<AuthenticationConfiguration> authenticationConfiguration,
            ObjectProvider<OAuth2AuthorizationService> authorizationService,
            ObjectProvider<RegisteredClientRepository> registeredClientRepository,
            ObjectProvider<JWKSource<SecurityContext>> jwkSource,
            ObjectProvider<AuthorizationServerSettings> authorizationServerSettings,
            ObjectProvider<JwtDecoder> jwtDecoder,
            IdentitySpi identitySpi,
            @Value("${marketplace.security.jwt.audience:marketplace-api}") String audience,
            @Value("${marketplace.security.oauth2.public-client.client-id:"
                    + "10b588c6-4e85-43ec-9ecf-c588676774d7}") String publicClientId) {
        this.authenticationConfiguration = authenticationConfiguration;
        this.authorizationService = authorizationService;
        this.registeredClientRepository = registeredClientRepository;
        this.jwkSource = jwkSource;
        this.authorizationServerSettings = authorizationServerSettings;
        this.jwtDecoder = jwtDecoder;
        this.identitySpi = identitySpi;
        this.audience = audience;
        this.publicClientId = publicClientId;
    }

    /** The issued pair + the caller's own account projection. */
    public record IssuedTokens(String accessToken, String refreshToken, String csrfToken,
                               UserAccountView user) {
    }

    /** The verification outcome (the VerifyToken payload's own shape). */
    public record VerifiedToken(boolean valid, Map<String, Object> claims, String subject) {
    }

    /**
     * The password grant: authenticate, mint the pair, persist the
     * authorization row against the public (dashboard) client.
     *
     * @throws AuthenticationException the provider's own semantics (bad
     *                                 credentials, disabled/locked account)
     */
    @Observed(name = "graphql.token.create")
    public IssuedTokens grant(String email, String password) {
        Authentication authentication = authenticationManager().authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        String principal = authentication.getName();
        Set<String> roles = stripRolePrefix(authentication);
        Instant now = Instant.now();
        String accessToken = mintAccessToken(principal, roles, now);
        String refreshToken = opaqueToken();
        authorizationService().save(authorizationRow(principal, roles, accessToken,
                refreshToken, now));
        UserAccountView user = identitySpi.findCurrentUserAccount(authentication).orElse(null);
        log.info("Platform password grant issued: principal={}, roles={}", principal, roles);
        return new IssuedTokens(accessToken, refreshToken, opaqueToken(), user);
    }

    /**
     * The refresh rotation: finds the authorization row by the refresh
     * token (the official {@code findByToken} API), verifies its life,
     * mints a fresh access token with the SAME principal/roles claims,
     * and saves the updated row.
     */
    @Observed(name = "graphql.token.refresh")
    public IssuedTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "A refresh token is required");
        }
        OAuth2Authorization authorization = authorizationService().findByToken(
                refreshToken, new OAuth2TokenType(OAuth2TokenType.REFRESH_TOKEN.getValue()));
        if (authorization == null
                || authorization.getRefreshToken() == null
                || authorization.getRefreshToken().isExpired()) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "The refresh token is invalid or expired");
        }
        String principal = authorization.getPrincipalName();
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) authorization.getAttributes()
                .getOrDefault(ROLES_ATTRIBUTE, Set.of());
        String accessToken = mintAccessToken(principal, roles, Instant.now());
        OAuth2AccessToken accessTokenToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessToken, Instant.now(),
                Instant.now().plus(ACCESS_TOKEN_TTL));
        OAuth2Authorization refreshed = OAuth2Authorization.from(authorization)
                .token(accessTokenToken, metadata ->
                        metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false))
                .build();
        authorizationService().save(refreshed);
        UserAccountView user = identitySpi.findCurrentUserAccount(
                        new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()))
                .orElse(null);
        log.info("Platform token refresh issued: principal={}", principal);
        return new IssuedTokens(accessToken, refreshToken, opaqueToken(), user);
    }

    /**
     * The verification: the resource server's own decoder — the same
     * validator every API request runs (issuer, audience, expiry). An
     * invalid or expired token answers the honest {@code isValid: false},
     * never an error.
     */
    @Observed(name = "graphql.token.verify")
    public VerifiedToken verify(String token) {
        try {
            Jwt jwt = jwtDecoder().decode(token);
            return new VerifiedToken(true, jwt.getClaims(), jwt.getSubject());
        } catch (JwtException failure) {
            return new VerifiedToken(false, Map.of(), null);
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private String mintAccessToken(String principal, Set<String> roles, Instant issuedAt) {
        AuthorizationServerSettings settings = authorizationServerSettings();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.getIssuer())
                .subject(principal)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ACCESS_TOKEN_TTL))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .build();
        return jwtEncoder().encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private OAuth2Authorization authorizationRow(String principal, Set<String> roles,
                                                  String accessToken, String refreshToken,
                                                  Instant issuedAt) {
        RegisteredClient client = registeredClientRepository().findByClientId(publicClientId);
        if (client == null) {
            throw new IllegalStateException(
                    "The public client is not registered — the password grant requires it");
        }
        OAuth2AccessToken accessTokenToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessToken, issuedAt,
                issuedAt.plus(ACCESS_TOKEN_TTL));
        OAuth2RefreshToken refreshTokenToken = new OAuth2RefreshToken(refreshToken, issuedAt,
                issuedAt.plus(REFRESH_TOKEN_TTL));
        return OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(principal)
                .authorizationGrantType(new AuthorizationGrantType("password"))
                .token(accessTokenToken)
                .refreshToken(refreshTokenToken)
                .attribute(ROLES_ATTRIBUTE, roles)
                .build();
    }

    private static Set<String> stripRolePrefix(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
                .collect(Collectors.toSet());
    }

    private AuthenticationManager authenticationManager() {
        AuthenticationConfiguration configuration = authenticationConfiguration.getIfAvailable();
        if (configuration == null) {
            throw new IllegalStateException(
                    "The authorization stack is not available — the token grant requires it");
        }
        try {
            return configuration.getAuthenticationManager();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "The global authentication manager could not be assembled", failure);
        }
    }

    private OAuth2AuthorizationService authorizationService() {
        OAuth2AuthorizationService service = authorizationService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException(
                    "The authorization service is not available — the token grant requires it");
        }
        return service;
    }

    private RegisteredClientRepository registeredClientRepository() {
        RegisteredClientRepository repository = registeredClientRepository.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException(
                    "The client registry is not available — the token grant requires it");
        }
        return repository;
    }

    private AuthorizationServerSettings authorizationServerSettings() {
        AuthorizationServerSettings settings = authorizationServerSettings.getIfAvailable();
        if (settings == null) {
            throw new IllegalStateException(
                    "The authorization server settings are not available — the token grant "
                            + "requires them");
        }
        return settings;
    }

    private JwtEncoder jwtEncoder() {
        JWKSource<SecurityContext> keys = jwkSource.getIfAvailable();
        if (keys == null) {
            throw new IllegalStateException(
                    "The signing key material is not available — the token grant requires it");
        }
        return new NimbusJwtEncoder(keys);
    }

    private JwtDecoder jwtDecoder() {
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder == null) {
            throw new IllegalStateException(
                    "The resource-server decoder is not available — token verification requires it");
        }
        return decoder;
    }

    private static String opaqueToken() {
        byte[] secret = new byte[32];
        SECURE_RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
}
