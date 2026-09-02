package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstraps the {@code marketplace-web-client} registered client from external configuration
 * (environment variables) into the {@code oauth2_registered_client} table at startup.
 *
 * <p>This is the single official mutation/bootstrapping path for a registered client:
 * {@link RegisteredClientRepository#save(RegisteredClient)} (Spring Authorization Server —
 * core-model-components, "RegisteredClientRepository"). The client definition lives in
 * {@code V13__authorization_security.sql} schema and application configuration, not in a
 * hand-written seed whose internal JSON format is owned by the framework (Jackson), not by
 * this application.
 *
 * <p><b>Bootstrap (client absent):</b> when no client is configured and the {@code prod}
 * profile is not active, startup is a deliberate no-op (there is no consumer until D9).
 * When configured, a missing client is <em>created</em> through the official builder
 * ({@link ClientSettings} / {@link TokenSettings}) rather than mutated SQL — the map is
 * never hand-written, so it cannot drift from what the framework serializes.
 *
 * <p><b>Converge-on-boot (client present):</b> the identity is taken from the existing row
 * via {@link RegisteredClient#from RegisteredClient.from(existing)} and the settings are
 * re-derived from the official builders ({@code requireProofKey(true)} +
 * {@code requireAuthorizationConsent(true)} + the operational TTL/refresh values). The old
 * map is <em>not</em> carried over ({@code from()}copies maps verbatim — a partial/legacy
 * map would preserve the id-token gap), so existing deployments converge on the complete
 * 8-key settings map at first startup.
 *
 * <p><b>Idempotence guard:</b> {@code save()} runs only when the raw secret differs from the
 * stored (encoded) value <em>or</em> the settings maps differ; matching secret + matching
 * settings is a no-op, so concurrent instances converge without rewriting identical rows.
 *
 * <p><b>Fail-fast by profile:</b> {@code application-prod.yml} binds the client from mandatory
 * environment variables ({@code OAUTH_CLIENT_ID}/{@code OAUTH_CLIENT_SECRET}), so production
 * must not silently run without a managed client. The nested
 * {@code marketplace.security.oauth2} section is bound non-null by an empty {@code @DefaultValue}
 * (official constructor-binding behavior), so it is safe to dereference in every profile.
 *
 * @see <a href="https://docs.spring.io/spring-authorization-server/reference/core-model-components.html#registered-client-repository">Spring Authorization Server — RegisteredClientRepository</a>
 */
@Component
public class OAuth2ClientSecretInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientSecretInitializer.class);

    private static final Profiles PROD_PROFILE = Profiles.of("prod");

    /**
     * Stable identity of the production web client. Referenced by
     * {@code oauth2_authorization.registered_client_id} and
     * {@code oauth2_authorization_consent.registered_client_id}, so it is invariant.
     */
    private static final String CLIENT_ID = "a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e";

    private static final String CLIENT_NAME = "Marketplace Web Client";
    private static final String REDIRECT_URI =
            "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client";
    private static final String POST_LOGOUT_REDIRECT_URI = "http://127.0.0.1:8080/";

    private final MarketplaceProperties properties;
    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public OAuth2ClientSecretInitializer(MarketplaceProperties properties,
                                         RegisteredClientRepository registeredClientRepository,
                                         PasswordEncoder passwordEncoder,
                                         Environment environment) {
        this.properties = properties;
        this.registeredClientRepository = registeredClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        MarketplaceProperties.Security.OAuth2.Client client = properties.security().oauth2().client();
        String clientId = client.clientId();
        String rawSecret = client.secret();

        if (!StringUtils.hasText(clientId) && !StringUtils.hasText(rawSecret)) {
            if (environment.acceptsProfiles(PROD_PROFILE)) {
                throw new IllegalStateException(
                        "marketplace.security.oauth2.client.clientId and .secret must be configured in production"
                                + " (OAUTH_CLIENT_ID/OAUTH_CLIENT_SECRET)");
            }
            return;
        }
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(rawSecret)) {
            throw new IllegalStateException(
                    "marketplace.security.oauth2.client.clientId and .secret must both be configured");
        }

        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        boolean secretChanged = existing == null || !passwordEncoder.matches(rawSecret, existing.getClientSecret());

        RegisteredClient target = buildTarget(existing, clientId, rawSecret, secretChanged);

        if (!needsSave(existing, target)) {
            return;
        }

        registeredClientRepository.save(target);

        if (existing == null) {
            log.info("Bootstrapped registered client '{}' from environment configuration", clientId);
        } else if (secretChanged) {
            log.info("Rotated client_secret for registered client '{}' from environment configuration", clientId);
        } else {
            log.info("Converged settings for registered client '{}' from environment configuration", clientId);
        }
    }

    private RegisteredClient buildTarget(RegisteredClient existing,
                                         String clientId,
                                         String rawSecret,
                                         boolean secretChanged) {
        if (existing == null) {
            return RegisteredClient.withId(CLIENT_ID)
                    .clientId(clientId)
                    .clientSecret(passwordEncoder.encode(rawSecret))
                    .clientName(CLIENT_NAME)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .redirectUri(REDIRECT_URI)
                    .postLogoutRedirectUri(POST_LOGOUT_REDIRECT_URI)
                    .scope("openid")
                    .scope("profile")
                    .clientSettings(buildClientSettings())
                    .tokenSettings(buildTokenSettings())
                    .build();
        }

        RegisteredClient.Builder builder = RegisteredClient.from(existing)
                .clientId(clientId)
                .clientSettings(buildClientSettings())
                .tokenSettings(buildTokenSettings());
        if (!secretChanged) {
            builder.clientSecret(existing.getClientSecret());
        } else {
            builder.clientSecret(passwordEncoder.encode(rawSecret));
        }
        return builder.build();
    }

    private static ClientSettings buildClientSettings() {
        return ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build();
    }

    private static TokenSettings buildTokenSettings() {
        return TokenSettings.builder()
                .reuseRefreshTokens(false)
                .accessTokenTimeToLive(Duration.ofSeconds(900))
                .refreshTokenTimeToLive(Duration.ofSeconds(604800))
                .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                .build();
    }

    private static boolean needsSave(RegisteredClient existing, RegisteredClient target) {
        if (existing == null) {
            return true;
        }
        if (!existing.getClientSecret().equals(target.getClientSecret())) {
            return true;
        }
        return !existing.getClientSettings().getSettings().equals(target.getClientSettings().getSettings())
                || !existing.getTokenSettings().getSettings().equals(target.getTokenSettings().getSettings());
    }
}
