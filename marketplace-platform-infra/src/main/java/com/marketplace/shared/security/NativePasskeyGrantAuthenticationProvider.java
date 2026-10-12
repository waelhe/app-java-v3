package com.marketplace.shared.security;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.stereotype.Component;

import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.util.StringUtils;

/**
 * Applies Spring Security's WebAuthn verifier, then delegates JWT encoding to
 * Spring Security's {@link JwtGenerator} adapter using the configured JWK source
 * and application claims customizer. Authorization persistence remains owned by
 * Spring Security's {@link OAuth2AuthorizationService}. The built-in grant
 * providers keep their framework-managed generator and default customizers.
 */
@Component
public final class NativePasskeyGrantAuthenticationProvider implements AuthenticationProvider {

    private final NativePasskeyService passkeyService;
    private final OAuth2AuthorizationService authorizationService;
    private final NativePasskeyAccessTokenGenerator tokenGenerator;
    private final UserDetailsService userDetailsService;
    private final MarketplaceProperties properties;

    public NativePasskeyGrantAuthenticationProvider(
            NativePasskeyService passkeyService,
            OAuth2AuthorizationService authorizationService,
            NativePasskeyAccessTokenGenerator tokenGenerator,
            UserDetailsService userDetailsService,
            MarketplaceProperties properties) {
        this.passkeyService = passkeyService;
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
        this.userDetailsService = userDetailsService;
        this.properties = properties;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        NativePasskeyGrantAuthenticationToken grant = (NativePasskeyGrantAuthenticationToken) authentication;
        OAuth2ClientAuthenticationToken clientPrincipal = authenticatedClient(grant);
        RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();

        String expectedClientId = properties.security().oauth2().publicClient().clientId();
        if (!StringUtils.hasText(expectedClientId)
                || !expectedClientId.equals(registeredClient.getClientId())
                || !registeredClient.getAuthorizationGrantTypes().contains(grant.getGrantType())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
        }
        if (registeredClient.getClientSecret() != null
                || !registeredClient.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)
                || !OAuth2TokenFormat.SELF_CONTAINED.equals(
                        registeredClient.getTokenSettings().getAccessTokenFormat())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
        }
        if (!registeredClient.getScopes().contains("profile")) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
        }

        Authentication userAuthentication = passkeyService.authenticate(grant.getChallengeId(),
                grant.getCredentialJson());
        if (userAuthentication == null || !userAuthentication.isAuthenticated()
                || !StringUtils.hasText(userAuthentication.getName())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }

        // WebAuthn proves possession of a credential, but current account flags
        // remain authoritative (email verification, disabled/locked/expired).
        final UserDetails account;
        try {
            account = this.userDetailsService.loadUserByUsername(userAuthentication.getName());
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
        if (!account.isEnabled() || !account.isAccountNonLocked()
                || !account.isAccountNonExpired() || !account.isCredentialsNonExpired()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }

        Set<String> authorizedScopes = Set.of("profile");
        OAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                .registeredClient(registeredClient)
                .principal(userAuthentication)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .tokenType(org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(grant.getGrantType())
                .authorizationGrant(grant)
                .build();

        var generatedToken = this.tokenGenerator.generate(tokenContext);
        if (generatedToken == null || !StringUtils.hasText(generatedToken.getTokenValue())) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.SERVER_ERROR,
                    "The token generator failed to generate an access token.",
                    null));
        }

        Instant issuedAt = generatedToken.getIssuedAt();
        Instant expiresAt = generatedToken.getExpiresAt();
        OAuth2AccessToken.TokenType tokenType = OAuth2AccessToken.TokenType.BEARER;
        if (generatedToken instanceof ClaimAccessor claimAccessor) {
            Map<String, Object> confirmation = claimAccessor.getClaimAsMap("cnf");
            if (confirmation != null && confirmation.containsKey("jkt")) {
                tokenType = OAuth2AccessToken.TokenType.DPOP;
            }
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                tokenType,
                generatedToken.getTokenValue(),
                issuedAt,
                expiresAt,
                authorizedScopes);
        OAuth2TokenFormat accessTokenFormat = registeredClient.getTokenSettings().getAccessTokenFormat();

        OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization
                .withRegisteredClient(registeredClient)
                .principalName(userAuthentication.getName())
                .authorizationGrantType(grant.getGrantType())
                .authorizedScopes(authorizedScopes);

        // Match Spring Security's built-in token providers: persist claims,
        // invalidation state, and the registered token format in the official
        // OAuth2Authorization metadata. Do not persist the WebAuthn Authentication
        // implementation as an arbitrary JDBC-serialized principal attribute.
        authorizationBuilder.token(accessToken, metadata -> {
            if (generatedToken instanceof ClaimAccessor claimAccessor) {
                metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claimAccessor.getClaims());
            }
            metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false);
            metadata.put(OAuth2TokenFormat.class.getName(), accessTokenFormat.getValue());
        });
        this.authorizationService.save(authorizationBuilder.build());

        return new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return NativePasskeyGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static OAuth2ClientAuthenticationToken authenticatedClient(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2ClientAuthenticationToken client && client.isAuthenticated()) {
            return client;
        }
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    }
}
