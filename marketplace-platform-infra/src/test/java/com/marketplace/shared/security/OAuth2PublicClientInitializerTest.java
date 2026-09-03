package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2PublicClientInitializerTest {

    private static final String PUBLIC_ROW_ID = "10b588c6-4e85-43ec-9ecf-c588676774d7";
    private static final String REDIRECT = "com.marketplace.test:/oauth2/callback";

    private final RegisteredClientRepository repository = mock(RegisteredClientRepository.class);

    @Test
    void doesNothingWhenPublicClientNotConfigured() {
        new OAuth2PublicClientInitializer(properties("", ""), repository, environment(false)).run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void failsWhenPublicClientNotConfiguredInProductionProfile() {
        OAuth2PublicClientInitializer prodInitializer =
                new OAuth2PublicClientInitializer(properties("", ""), repository, environment(true));

        assertThatThrownBy(() -> prodInitializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured in production");
    }

    @Test
    void failsWhenOnlyClientIdConfiguredWithoutRedirectUris() {
        OAuth2PublicClientInitializer partial =
                new OAuth2PublicClientInitializer(properties("mobile", ""), repository, environment(false));

        assertThatThrownBy(() -> partial.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must both be configured");
    }

    @Test
    void failsWhenOnlyRedirectUrisConfiguredWithoutClientId() {
        OAuth2PublicClientInitializer partial =
                new OAuth2PublicClientInitializer(properties("", REDIRECT), repository, environment(false));

        assertThatThrownBy(() -> partial.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must both be configured");
    }

    @Test
    void bootstrapsPublicClientWithStableIdAndOfficialDefinitionWhenAbsent() {
        when(repository.findByClientId("mobile")).thenReturn(null);

        new OAuth2PublicClientInitializer(properties("mobile", REDIRECT + " , " + "https://app.test/callback"),
                repository, environment(false)).run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getId()).isEqualTo(PUBLIC_ROW_ID);
        assertThat(saved.getClientSecret())
                .as("a public client has no secret by definition")
                .isNull();
        assertThat(saved.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(saved.getAuthorizationGrantTypes())
                .as("public clients get no refresh grant (SAS how-to, gh-297)")
                .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(saved.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(saved.getRedirectUris())
                .as("comma-separated env value is split, trimmed and blanks dropped"
                        + " (RegisteredClient stores them in a set, so order is not guaranteed)")
                .containsExactlyInAnyOrder(REDIRECT, "https://app.test/callback");
        assertThat(saved.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(saved.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(saved.getTokenSettings().getSettings().get("settings.token.access-token-time-to-live"))
                .isEqualTo(Duration.ofSeconds(900));
        assertThat(saved.getTokenSettings().getSettings().get("settings.token.authorization-code-time-to-live"))
                .isEqualTo(Duration.ofSeconds(300));
        assertThat(saved.getTokenSettings().getIdTokenSignatureAlgorithm()).isNotNull();
        assertThat(saved.getTokenSettings().getAccessTokenFormat()).isNotNull();
    }

    @Test
    void doesNothingWhenStoredDefinitionAlreadyMatches() {
        when(repository.findByClientId("mobile")).thenReturn(publicClientRow(UUID.randomUUID().toString(), REDIRECT));

        new OAuth2PublicClientInitializer(properties("mobile", REDIRECT), repository, environment(false)).run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void convergesEnvDrivenRedirectUrisWhilePreservingRowIdentity() {
        String existingRowId = UUID.randomUUID().toString();
        RegisteredClient existing = publicClientRow(existingRowId, "com.old.scheme:/callback");
        when(repository.findByClientId("mobile")).thenReturn(existing);

        new OAuth2PublicClientInitializer(properties("mobile", REDIRECT), repository, environment(false)).run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getId())
                .as("converge re-derives the definition but preserves the row identity")
                .isEqualTo(existingRowId);
        assertThat(saved.getRedirectUris()).containsExactly(REDIRECT);
    }

    @Test
    void convergesForeignRowWithSecretBackToSecretlessDefinition() {
        String existingRowId = UUID.randomUUID().toString();
        RegisteredClient rogue = RegisteredClient.withId(existingRowId)
                .clientId("mobile")
                .clientSecret("rogue-secret")
                .clientName("Rogue")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT)
                .scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(false).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
        when(repository.findByClientId("mobile")).thenReturn(rogue);

        new OAuth2PublicClientInitializer(properties("mobile", REDIRECT), repository, environment(false)).run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getId()).isEqualTo(existingRowId);
        assertThat(saved.getClientSecret()).isNull();
        assertThat(saved.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(saved.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(saved.getClientSettings().isRequireProofKey()).isTrue();
    }

    private static Environment environment(boolean prodProfileActive) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(prodProfileActive);
        return environment;
    }

    private RegisteredClient savedClientArgument() {
        org.mockito.ArgumentCaptor<RegisteredClient> captor =
                org.mockito.ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static RegisteredClient publicClientRow(String id, String redirectUri) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId("mobile")
                .clientName("Marketplace Public Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid")
                .scope("profile")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build());
        return builder.tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(900))
                        .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                        .build())
                .build();
    }

    private static MarketplaceProperties properties(String clientId, String redirectUris) {
        return new MarketplaceProperties(
                null,
                new MarketplaceProperties.Security(
                        null,
                        null,
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient(clientId, redirectUris))));
    }
}
