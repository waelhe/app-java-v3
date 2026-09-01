package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2ClientSecretInitializerTest {

    private final MarketplaceProperties properties = properties("", "");
    private final RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final OAuth2ClientSecretInitializer initializer =
            new OAuth2ClientSecretInitializer(properties, repository, passwordEncoder, environment(false));

    @Test
    void doesNothingWhenClientNotConfigured() {
        initializer.run(null);
        verify(repository, never()).save(any());
    }

    @Test
    void failsWhenClientNotConfiguredInProductionProfile() {
        OAuth2ClientSecretInitializer prodInitializer = new OAuth2ClientSecretInitializer(
                properties, repository, passwordEncoder, environment(true));

        assertThatThrownBy(() -> prodInitializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured in production");
    }

    @Test
    void doesNothingWhenSecretAlreadyMatchesStoredValue() {
        RegisteredClient existing = registeredClient("enc");
        when(repository.findByClientId("web")).thenReturn(existing);
        when(passwordEncoder.matches("raw", "enc")).thenReturn(true);

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void rotatesSecretWhenStoredValueDiffers() {
        RegisteredClient existing = registeredClient("old");
        when(repository.findByClientId("web")).thenReturn(existing);
        when(passwordEncoder.matches("raw", "old")).thenReturn(false);
        when(passwordEncoder.encode("raw")).thenReturn("new");

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        verify(repository).save(any());
    }

    @Test
    void failsWhenOnlyOneOfClientIdSecretConfigured() {
        OAuth2ClientSecretInitializer partial = new OAuth2ClientSecretInitializer(
                properties("web", ""), repository, passwordEncoder, environment(false));

        assertThatThrownBy(() -> partial.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must both be configured");
    }

    @Test
    void failsWhenConfiguredClientIdNotFound() {
        when(repository.findByClientId("missing")).thenReturn(null);

        OAuth2ClientSecretInitializer missing = new OAuth2ClientSecretInitializer(
                properties("missing", "raw"), repository, passwordEncoder, environment(false));

        assertThatThrownBy(() -> missing.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Registered client not found");
    }

    private static Environment environment(boolean prodProfileActive) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(prodProfileActive);
        return environment;
    }

    private static RegisteredClient registeredClient(String encodedSecret) {
        return RegisteredClient.withId("id")
                .clientId("web")
                .clientSecret(encodedSecret)
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("read")
                .build();
    }

    private static MarketplaceProperties properties(String clientId, String secret) {
        return new MarketplaceProperties(
                null,
                new MarketplaceProperties.Security(
                        null,
                        null,
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client(clientId, secret))));
    }
}
