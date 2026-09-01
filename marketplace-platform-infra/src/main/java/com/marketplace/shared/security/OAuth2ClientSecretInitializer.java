package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstraps the {@code marketplace-web-client} secret from external configuration
 * (environment variable) into the {@code oauth2_registered_client} table at startup.
 *
 * <p>Rationale (official design): the Spring Authorization Server schema keeps
 * {@code client_secret varchar(200) DEFAULT NULL}, and the JVM reads it back from the
 * database via {@link RegisteredClientRepository}. The single official mutation path for a
 * registered client is {@link RegisteredClientRepository#save(RegisteredClient)}, which
 * orchestrates the framework's UPDATE (secret + {@code clientSecretExpiresAt} together).
 * Feeding the secret through environment/configuration keeps it out of version control and
 * follows the project's env-driven {@code application-prod.yml} precedent (e.g.
 * {@code JWT_KEYSTORE_PASSWORD}).
 *
 * <p>Idempotent and cluster-safe: the update is applied only when the raw secret does not
 * already match the stored (encoded) value, so concurrent instances converge without
 * rewriting identical secrets.
 *
 * <p>Fail-fast by profile: {@code application-prod.yml} binds the client secret from
 * mandatory environment variables ({@code OAUTH_CLIENT_ID}/{@code OAUTH_CLIENT_SECRET}),
 * so production must not silently run on the seed placeholder. If the client is not
 * configured and the {@code prod} profile is active, startup fails. Absent configuration
 * in non-production profiles (dev, test) is a deliberate no-op, keeping the seed secret.
 *
 * @see <a href="https://docs.spring.io/spring-authorization-server/reference/core-model-components.html#registered-client-repository">Spring Authorization Server — RegisteredClientRepository</a>
 */
@Component
public class OAuth2ClientSecretInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientSecretInitializer.class);

    private static final Profiles PROD_PROFILE = Profiles.of("prod");

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
        if (existing == null) {
            throw new IllegalStateException(
                    "Registered client not found for configured marketplace.security.oauth2.client.clientId=" + clientId);
        }

        if (passwordEncoder.matches(rawSecret, existing.getClientSecret())) {
            return;
        }

        String encoded = passwordEncoder.encode(rawSecret);
        registeredClientRepository.save(RegisteredClient.from(existing)
                .clientSecret(encoded)
                .clientSecretExpiresAt(null)
                .build());

        log.info("Rotated client_secret for registered client '{}' from environment configuration", clientId);
    }
}
