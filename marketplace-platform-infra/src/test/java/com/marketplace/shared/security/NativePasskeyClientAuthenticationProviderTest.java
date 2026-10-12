package com.marketplace.shared.security;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import com.marketplace.shared.config.MarketplaceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativePasskeyClientAuthenticationProviderTest {

    private static final String CLIENT_ID = "android-community-native";
    private final RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
    private final NativePasskeyClientAuthenticationProvider provider =
            new NativePasskeyClientAuthenticationProvider(repository, properties(CLIENT_ID));

    @Test
    void authenticatesOnlyTheConfiguredPublicClientShape() {
        RegisteredClient registeredClient = publicClient();
        when(repository.findByClientId(CLIENT_ID)).thenReturn(registeredClient);

        OAuth2ClientAuthenticationToken result = (OAuth2ClientAuthenticationToken) this.provider.authenticate(
                clientRequest(CLIENT_ID));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getRegisteredClient()).isSameAs(registeredClient);
        assertThat(result.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAdditionalParameters()).isEmpty();
    }

    @Test
    void rejectsUnregisteredClient() {
        when(repository.findByClientId(CLIENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> this.provider.authenticate(clientRequest(CLIENT_ID)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    @Test
    void rejectsRegisteredClientThatDoesNotMatchTheNativeContract() {
        RegisteredClient wrongShape = RegisteredClient.from(publicClient())
                .clientSecret("unexpected-secret")
                .build();
        when(repository.findByClientId(CLIENT_ID)).thenReturn(wrongShape);

        assertThatThrownBy(() -> this.provider.authenticate(clientRequest(CLIENT_ID)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    @Test
    void leavesOrdinaryClientAuthenticationForSpringSecurityProviders() {
        OAuth2ClientAuthenticationToken ordinary = new OAuth2ClientAuthenticationToken(
                CLIENT_ID, ClientAuthenticationMethod.NONE, null, java.util.Map.of());

        assertThat(this.provider.authenticate(ordinary)).isNull();
    }

    private static OAuth2ClientAuthenticationToken clientRequest(String clientId) {
        return new OAuth2ClientAuthenticationToken(
                clientId,
                ClientAuthenticationMethod.NONE,
                null,
                java.util.Map.of(NativePasskeyClientAuthenticationConverter.CLIENT_AUTHENTICATION_MARKER, true));
    }

    private static RegisteredClient publicClient() {
        return RegisteredClient.withId("native-client-row-id")
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(NativePasskeyGrantAuthenticationToken.GRANT_TYPE)
                .scope("openid")
                .scope("profile")
                .redirectUri("com.marketplace.android:/oauth2/callback")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED).build())
                .build();
    }

    private static MarketplaceProperties properties(String clientId) {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                "marketplace-api"),
                        new MarketplaceProperties.Security.Session(2, false),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient(
                                        clientId, "com.marketplace.android:/oauth2/callback")),
                        new MarketplaceProperties.Security.Pseudonymization("", List.of()),
                        new MarketplaceProperties.Security.AdminSeed("")),
                new MarketplaceProperties.ApiVersioning(java.util.Map.of()));
    }
}
