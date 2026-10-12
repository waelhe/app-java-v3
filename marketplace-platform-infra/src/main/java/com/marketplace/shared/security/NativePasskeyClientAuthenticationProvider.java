package com.marketplace.shared.security;

import java.util.Set;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.marketplace.shared.config.MarketplaceProperties;

/**
 * Authenticates only the public client identity for the native passkey grant.
 * This uses Spring Security Authorization Server's documented client
 * AuthenticationProvider extension point. User authentication still requires
 * a verified WebAuthn assertion in the grant provider.
 */
@Component
public final class NativePasskeyClientAuthenticationProvider implements AuthenticationProvider {

    private final RegisteredClientRepository registeredClientRepository;
    private final MarketplaceProperties properties;

    public NativePasskeyClientAuthenticationProvider(
            RegisteredClientRepository registeredClientRepository,
            MarketplaceProperties properties) {
        this.registeredClientRepository = registeredClientRepository;
        this.properties = properties;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof OAuth2ClientAuthenticationToken client)
                || !Boolean.TRUE.equals(client.getAdditionalParameters().get(
                        NativePasskeyClientAuthenticationConverter.CLIENT_AUTHENTICATION_MARKER))) {
            return null;
        }
        if (!ClientAuthenticationMethod.NONE.equals(client.getClientAuthenticationMethod())
                || client.getCredentials() != null) {
            throw invalidClient();
        }

        String expectedClientId = this.properties.security().oauth2().publicClient().clientId();
        String clientId = client.getPrincipal().toString();
        if (!StringUtils.hasText(expectedClientId) || !expectedClientId.equals(clientId)) {
            throw invalidClient();
        }

        final RegisteredClient registeredClient;
        try {
            registeredClient = this.registeredClientRepository.findByClientId(clientId);
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.SERVER_ERROR,
                    "Native client authentication is temporarily unavailable.",
                    null), exception);
        }
        if (registeredClient == null
                || registeredClient.getClientSecret() != null
                || !registeredClient.getClientAuthenticationMethods().equals(Set.of(ClientAuthenticationMethod.NONE))
                || !registeredClient.getAuthorizationGrantTypes().equals(Set.of(
                        AuthorizationGrantType.AUTHORIZATION_CODE,
                        NativePasskeyGrantAuthenticationToken.GRANT_TYPE))
                || !registeredClient.getClientSettings().isRequireProofKey()
                || !registeredClient.getClientSettings().isRequireAuthorizationConsent()
                || !registeredClient.getScopes().equals(Set.of("openid", "profile"))
                || !OAuth2TokenFormat.SELF_CONTAINED.equals(
                        registeredClient.getTokenSettings().getAccessTokenFormat())) {
            throw invalidClient();
        }

        return new OAuth2ClientAuthenticationToken(registeredClient, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static OAuth2AuthenticationException invalidClient() {
        return new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    }
}
