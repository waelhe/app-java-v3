package com.marketplace.shared.security;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.stereotype.Component;

import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.util.StringUtils;

/**
 * Applies Spring Security's WebAuthn verifier, then delegates access-token
 * creation and persistence to Spring Authorization Server's configured token
 * generator and authorization service.
 */
@Component
public final class NativePasskeyGrantAuthenticationProvider implements AuthenticationProvider {

    private final NativePasskeyService passkeyService;
    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<?> tokenGenerator;
    private final UserDetailsService userDetailsService;
    private final MarketplaceProperties properties;

    public NativePasskeyGrantAuthenticationProvider(
            NativePasskeyService passkeyService,
            OAuth2AuthorizationService authorizationService,
            OAuth2TokenGenerator<?> tokenGenerator,
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
        UserDetails account = this.userDetailsService.loadUserByUsername(userAuthentication.getName());
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
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                generatedToken.getTokenValue(),
                issuedAt,
                expiresAt,
                authorizedScopes);

        OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization
                .withRegisteredClient(registeredClient)
                .principalName(userAuthentication.getName())
                .authorizationGrantType(grant.getGrantType())
                .authorizedScopes(authorizedScopes)
                .attribute(java.security.Principal.class.getName(), userAuthentication);

        if (generatedToken instanceof ClaimAccessor claimAccessor) {
            authorizationBuilder.token(accessToken, metadata -> metadata.put(
                    OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claimAccessor.getClaims()));
        } else {
            authorizationBuilder.accessToken(accessToken);
        }
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
