package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Registers and converges the public native-app client through Spring Security's
 * {@link RegisteredClientRepository} API. The client is derived from external
 * configuration and the current security contract, then persisted by the framework
 * repository; no credentials or client definition are embedded in the Android APK.
 *
 * <p>The client uses {@code ClientAuthenticationMethod.NONE}; a native application
 * cannot keep a client secret confidential. Its standard sign-in path remains
 * Authorization Code with PKCE as required by RFC 8252 and RFC 9700. The native
 * passkey sign-in path is an additional OAuth extension grant wired through the
 * documented Spring Security Authorization Server token-endpoint extension points.
 * It receives no refresh-token grant and uses a self-contained JWT access token
 * with the same short lifetime as the public client.</p>
 *
 * <p>Redirect URIs are externally configured because they identify the client
 * application and differ between deployment targets. On each startup, the complete
 * definition is derived again from configuration; {@code save()} is invoked only
 * when the persisted definition differs. The existing database row identity is
 * preserved to keep authorization foreign keys valid.</p>
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html">Spring Security Authorization Server — Core Model / Components</a>
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/protocol-endpoints.html">Spring Security Authorization Server — Protocol Endpoints</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc9700#section-2.1.1">RFC 9700 §2.1.1 — PKCE for public clients</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8252">RFC 8252 — OAuth 2.0 for Native Apps</a>
 */
@Component
public class OAuth2PublicClientInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OAuth2PublicClientInitializer.class);

    private static final Profiles PROD_PROFILE = Profiles.of("prod");

    /**
     * Stable row identity of the public client. Referenced by
     * {@code oauth2_authorization.registered_client_id} and
     * {@code oauth2_authorization_consent.registered_client_id}, so it is invariant —
     * same deliberate position as the confidential client's constant id (spec §4.1:
     * turns the worst-case boot race into a loud, retryable failure because
     * {@code client_id} has no UNIQUE constraint).
     */
    private static final String PUBLIC_CLIENT_ID = "10b588c6-4e85-43ec-9ecf-c588676774d7";

    /** Display name classified by secret location (plan §3), not by client technology. */
    private static final String CLIENT_NAME = "Marketplace Public Client";

    private final MarketplaceProperties properties;
    private final RegisteredClientRepository registeredClientRepository;
    private final Environment environment;

    public OAuth2PublicClientInitializer(MarketplaceProperties properties,
                                         RegisteredClientRepository registeredClientRepository,
                                         Environment environment) {
        this.properties = properties;
        this.registeredClientRepository = registeredClientRepository;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        MarketplaceProperties.Security.OAuth2.PublicClient client =
                properties.security().oauth2().publicClient();
        String clientId = client.clientId();
        Set<String> redirectUris = parseRedirectUris(client.redirectUris());

        if (!StringUtils.hasText(clientId) && redirectUris.isEmpty()) {
            if (environment.acceptsProfiles(PROD_PROFILE)) {
                throw new IllegalStateException(
                        "marketplace.security.oauth2.public-client.clientId and .redirectUris must be"
                                + " configured in production (OAUTH_PUBLIC_CLIENT_ID/OAUTH_PUBLIC_CLIENT_REDIRECT_URIS)");
            }
            return;
        }
        if (!StringUtils.hasText(clientId) || redirectUris.isEmpty()) {
            throw new IllegalStateException(
                    "marketplace.security.oauth2.public-client.clientId and .redirectUris must both be configured"
                            + " (OAUTH_PUBLIC_CLIENT_ID/OAUTH_PUBLIC_CLIENT_REDIRECT_URIS, comma-separated)");
        }

        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        RegisteredClient target = buildTarget(existing, clientId, redirectUris);

        if (!needsSave(existing, target)) {
            return;
        }

        registeredClientRepository.save(target);

        if (existing == null) {
            log.info("Bootstrapped public (secret-less) registered client '{}' from environment configuration",
                    clientId);
        } else {
            log.info("Converged public (secret-less) registered client '{}' from environment configuration",
                    clientId);
        }
    }

    private static RegisteredClient buildTarget(RegisteredClient existing,
                                                String clientId,
                                                Set<String> redirectUris) {
        String id = existing == null ? PUBLIC_CLIENT_ID : existing.getId();
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(clientId)
                .clientName(CLIENT_NAME)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(NativePasskeyGrantAuthenticationToken.GRANT_TYPE);
        redirectUris.forEach(builder::redirectUri);
        return builder
                .scope("openid")
                .scope("profile")
                .clientSettings(buildClientSettings())
                .tokenSettings(buildTokenSettings())
                .build();
    }

    /**
     * {@code requireProofKey(true)} enforces PKCE for this public client (RFC 9700 §2.1.1).
     * Spring Security Authorization Server exposes this through the documented
     * {@code ClientSettings} API. Consent is also required for this registered client.
     *
     * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html">Spring Security Authorization Server — Core Model / Components</a>
     */
    private static ClientSettings buildClientSettings() {
        return ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build();
    }

    /**
     * This client intentionally does not register {@code refresh_token} as an
     * authorization grant. Spring Security Authorization Server's authorization-code
     * provider creates a refresh token only when the registered client permits the
     * {@code refresh_token} grant; this client therefore receives no refresh token.
     *
     * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html">Spring Security Authorization Server — Core Model / Components</a>
     * @see <a href="https://github.com/spring-projects/spring-security/blob/7.1.1/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationCodeAuthenticationProvider.java">Spring Security 7.1.1 authorization-code provider</a>
     */
    private static TokenSettings buildTokenSettings() {
        return TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .accessTokenTimeToLive(Duration.ofSeconds(900))
                .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                .build();
    }

    /**
     * Full-definition comparison (spec §4.1 idempotence guard, extended to the fields the
     * environment drives): {@code save} happens if-and-only-if the stored row differs from
     * the derived definition in identity-relevant fields or the settings maps.
     */
    private static boolean needsSave(RegisteredClient existing, RegisteredClient target) {
        if (existing == null) {
            return true;
        }
        return !Objects.equals(existing.getClientId(), target.getClientId())
                || !Objects.equals(existing.getClientName(), target.getClientName())
                || !Objects.equals(existing.getClientSecret(), target.getClientSecret())
                || !Objects.equals(existing.getRedirectUris(), target.getRedirectUris())
                || !Objects.equals(existing.getPostLogoutRedirectUris(), target.getPostLogoutRedirectUris())
                || !Objects.equals(existing.getClientAuthenticationMethods(),
                        target.getClientAuthenticationMethods())
                || !Objects.equals(existing.getAuthorizationGrantTypes(),
                        target.getAuthorizationGrantTypes())
                || !Objects.equals(existing.getScopes(), target.getScopes())
                || !Objects.equals(existing.getClientSettings().getSettings(),
                        target.getClientSettings().getSettings())
                || !Objects.equals(existing.getTokenSettings().getSettings(),
                        target.getTokenSettings().getSettings());
    }

    /**
     * Splits the comma-separated {@code OAUTH_PUBLIC_CLIENT_REDIRECT_URIS} value; drops
     * blank entries, preserves order, keeps the exact registered text (the authorize
     * endpoint matches redirect URIs textually — spec §4.1).
     */
    private static Set<String> parseRedirectUris(String raw) {
        Set<String> uris = new LinkedHashSet<>();
        if (!StringUtils.hasText(raw)) {
            return uris;
        }
        for (String candidate : raw.split(",")) {
            if (StringUtils.hasText(candidate)) {
                uris.add(candidate.trim());
            }
        }
        return uris;
    }
}
