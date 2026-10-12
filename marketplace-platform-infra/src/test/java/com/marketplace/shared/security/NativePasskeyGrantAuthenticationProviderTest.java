package com.marketplace.shared.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import com.marketplace.shared.config.MarketplaceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativePasskeyGrantAuthenticationProviderTest {

    private static final String CLIENT_ID = "android-community-native";
    private static final String USERNAME = "consumer@example.com";

    private final NativePasskeyService passkeyService = mock(NativePasskeyService.class);
    private final OAuth2AuthorizationService authorizationService = mock(OAuth2AuthorizationService.class);
    private final OAuth2AuthorizationConsentService consentService =
            mock(OAuth2AuthorizationConsentService.class);
    private final OAuth2TokenGenerator<?> tokenGenerator = mock(OAuth2TokenGenerator.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);

    private NativePasskeyGrantAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NativePasskeyGrantAuthenticationProvider(
                passkeyService, authorizationService, consentService,
                tokenGenerator, userDetailsService, propertiesWithClient(CLIENT_ID));
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return "https://auth.example.com";
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return AuthorizationServerSettings.builder().build();
            }
        });
    }

    @AfterEach
    void tearDown() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void supportsOnlyTheNativePasskeyGrantToken() {
        assertThat(provider.supports(NativePasskeyGrantAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(TestingAuthenticationToken.class)).isFalse();
    }

    @Test
    void rejectsAPrincipalThatIsNotAnAuthenticatedClient() {
        NativePasskeyGrantAuthenticationToken grant =
                new NativePasskeyGrantAuthenticationToken(
                        new TestingAuthenticationToken("not-a-client", "n/a"),
                        "47fc1bd0-4b40-4b0b-a35b-eebc05b6f920", "{}", Set.of());

        assertThatThrownBy(() -> provider.authenticate(grant))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    @Test
    void rejectsAClientOtherThanTheConfiguredPublicClient() {
        provider = new NativePasskeyGrantAuthenticationProvider(
                passkeyService, authorizationService, consentService,
                tokenGenerator, userDetailsService, propertiesWithClient("some-other-client"));

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT));
    }

    @Test
    void rejectsClientsThatCarryASecretInsteadOfThePublicShape() {
        RegisteredClient withSecret = RegisteredClient.from(publicClient())
                .clientSecret("leaked-secret")
                .build();

        assertThatThrownBy(
                () -> provider.authenticate(grant(withSecret, Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT));
    }

    @Test
    void rejectsScopesBeyondTheApiProfileScope() {
        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of("openid"))))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_SCOPE));
    }

    @Test
    void rejectsAnUnverifiedAssertion() {
        when(passkeyService.authenticate(any(), any())).thenReturn(null);

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void rejectsAnAccountThatCannotBeLoaded() {
        when(passkeyService.authenticate(any(), any())).thenReturn(webAuthnUser());
        when(userDetailsService.loadUserByUsername(USERNAME))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException(USERNAME));

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void returnsServerErrorWhenAccountStoreIsUnavailable() {
        when(passkeyService.authenticate(any(), any())).thenReturn(webAuthnUser());
        when(userDetailsService.loadUserByUsername(USERNAME))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
    }

    @Test
    void rejectsADisabledAccount() {
        when(passkeyService.authenticate(any(), any())).thenReturn(webAuthnUser());
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(User.withUsername(USERNAME)
                .password("n/a").disabled(true).roles("CONSUMER").build());

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void rejectsWithoutAProfileConsentForThisClient() {
        when(passkeyService.authenticate(any(), any())).thenReturn(webAuthnUser());
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(consentService.findById(CLIENT_ID, USERNAME))
                .thenReturn(OAuth2AuthorizationConsent.withId(CLIENT_ID, USERNAME)
                        .scope("openid").build());

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void returnsServerErrorWhenConsentStoreIsUnavailable() {
        when(passkeyService.authenticate(any(), any())).thenReturn(webAuthnUser());
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(consentService.findById(CLIENT_ID, USERNAME))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
        verify(tokenGenerator, never()).generate(any());
    }

    @Test
    void returnsServerErrorWhenAuthorizationStoreIsUnavailable() {
        stubHappyPath();
        OAuth2AccessToken generated = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "token-value", Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES),
                Set.of("profile"));
        when(tokenGenerator.generate(any())).thenReturn(generated);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(authorizationService).save(any());

        assertThatThrownBy(() -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
    }

    @Test
    void rejectsWhenTheSharedTokenGeneratorFails() {
        stubHappyPath();
        when(tokenGenerator.generate(any())).thenReturn(null);

        assertThatThrownBy(
                () -> provider.authenticate(grant(publicClient(), Set.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR));
        verify(authorizationService, never()).save(any());
    }

    @Test
    void issuesTheAccessTokenThroughTheSharedGeneratorAndPersistsTheAuthorization() {
        stubHappyPath();
        OAuth2AccessToken generated = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "token-value", Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES),
                Set.of("profile"));
        when(tokenGenerator.generate(any())).thenReturn(generated);

        Authentication result = provider.authenticate(grant(publicClient(), Set.of("profile")));

        assertThat(result).isInstanceOf(OAuth2AccessTokenAuthenticationToken.class);
        OAuth2AccessTokenAuthenticationToken token = (OAuth2AccessTokenAuthenticationToken) result;
        assertThat(token.getAccessToken().getTokenValue()).isEqualTo("token-value");
        assertThat(token.getAccessToken().getScopes()).containsExactly("profile");
        assertThat(token.getPrincipal()).isInstanceOf(OAuth2ClientAuthenticationToken.class);

        ArgumentCaptor<OAuth2Authorization> saved = ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(authorizationService).save(saved.capture());
        OAuth2Authorization authorization = saved.getValue();
        assertThat(authorization.getRegisteredClientId()).isEqualTo(CLIENT_ID);
        assertThat(authorization.getPrincipalName()).isEqualTo(USERNAME);
        assertThat(authorization.getAuthorizedScopes()).containsExactly("profile");
        assertThat(authorization.getAccessToken().isInvalidated()).isFalse();
    }

    private void stubHappyPath() {
        when(passkeyService.authenticate(any(), any())).thenReturn(webAuthnUser());
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(consentService.findById(CLIENT_ID, USERNAME))
                .thenReturn(OAuth2AuthorizationConsent.withId(CLIENT_ID, USERNAME)
                        .scope("profile").build());
    }

    private static NativePasskeyGrantAuthenticationToken grant(
            RegisteredClient registeredClient, Set<String> scopes) {
        OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                registeredClient, ClientAuthenticationMethod.NONE, "not-a-secret");
        return new NativePasskeyGrantAuthenticationToken(
                clientPrincipal, "47fc1bd0-4b40-4b0b-a35b-eebc05b6f920", "{}", scopes);
    }

    /**
     * The exact public-client shape the provider pins: secret-less, PKCE and consent
     * required, only the authorization-code and passkey grants, the official pair of
     * OIDC scopes, and a self-contained access token.
     */
    private static RegisteredClient publicClient() {
        return RegisteredClient.withId(CLIENT_ID)
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(NativePasskeyGrantAuthenticationToken.GRANT_TYPE)
                .scope("openid")
                .scope("profile")
                .redirectUri("com.marketplace.android:/oauth2/callback")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .build())
                .build();
    }

    private static Authentication webAuthnUser() {
        return new TestingAuthenticationToken(USERNAME, "n/a", "ROLE_CONSUMER");
    }

    private static UserDetails enabledUser() {
        return User.withUsername(USERNAME).password("n/a").roles("CONSUMER").build();
    }

    private static MarketplaceProperties propertiesWithClient(String clientId) {
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
