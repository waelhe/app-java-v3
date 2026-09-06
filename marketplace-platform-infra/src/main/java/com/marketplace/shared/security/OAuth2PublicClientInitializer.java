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
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstraps the public (secret-less) registered client — gate B, pattern (3) "native
 * application" of the client hosting strategy plan (§4: classified by secret location,
 * not by technology) — from external configuration into {@code oauth2_registered_client}
 * at startup, through the same single official mutation path as
 * {@link OAuth2ClientSecretInitializer}: {@link RegisteredClientRepository#save(RegisteredClient)}
 * (Spring Authorization Server — core-model-components, "RegisteredClientRepository").
 *
 * <p><b>Public client definition (official constraints):</b> a public client "cannot
 * securely store credentials" (SAS how-to how-to-pkce) so this registration carries
 * <ul>
 *   <li>{@code clientAuthenticationMethod: none} and no client secret (the column is
 *       nullable in the application's own {@code V13__authorization_security.sql});</li>
 *   <li>{@code requireProofKey(true)} — public clients MUST use PKCE (RFC 9700 §2.1.1;
 *       the SAS how-to: "The requireProofKey setting is important to prevent the PKCE
 *       Downgrade Attack");</li>
 *   <li>{@code authorization_code} grant only — "Spring Authorization Server will not
 *       issue refresh tokens for a public client" (SAS how-to how-to-pkce, gh-297), so
 *       neither the refresh grant nor refresh {@link TokenSettings} are set: the access
 *       token is the only bearer credential, and re-authentication goes through the
 *       authorization-server session (short access TTL, honest trade-off documented in
 *       the plan §4);</li>
 *   <li>redirect URIs are <em>environment-driven</em> ({@code OAUTH_PUBLIC_CLIENT_REDIRECT_URIS},
 *       comma-separated; custom scheme per RFC 8252 or an https app link) — unlike the
 *       web client's constant, because the redirect belongs to the client application's
 *       identity and changes per deployment.</li>
 * </ul>
 *
 * <p><b>Bootstrap (row absent):</b> created through the official
 * {@link RegisteredClient.Builder} with a fixed id (see below). When not configured and
 * the {@code prod} profile is not active, startup is a deliberate no-op — same contract
 * as the confidential client initializer.
 *
 * <p><b>Converge-on-boot (row present):</b> the full definition is re-derived from
 * configuration and code constants and rebuilt with {@code RegisteredClient.withId(existing.getId())}
 * — the identity-preserving equivalent of the spec's {@code RegisteredClient.from(existing)}
 * "identity only" prescription (§4.1 تثبيت ب), chosen because this definition is
 * environment-driven: nothing from the stored row (grants, redirect URIs, settings maps)
 * is carried over, so an environment change (e.g. a new redirect URI) converges at the
 * next boot without legacy drift.
 *
 * <p><b>Idempotence guard:</b> {@code save()} runs only when the derived definition
 * differs from the stored row — client id, client name, redirect URIs, authentication
 * methods, grant types, scopes, secret null-ness, or the settings maps. Matching rows are
 * a no-op, so concurrent instances converge without rewriting identical rows.
 *
 * <p><b>Fail-fast by profile:</b> {@code application-prod.yml} binds both values from
 * mandatory environment variables ({@code OAUTH_PUBLIC_CLIENT_ID} /
 * {@code OAUTH_PUBLIC_CLIENT_REDIRECT_URIS}); production must not silently run without
 * the first (public) client. The nested {@code marketplace.security.oauth2.public-client}
 * section is bound non-null by an empty {@code @DefaultValue}.
 *
 * @see <a href="https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html">Spring Authorization Server — How-to: PKCE</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc9700#section-2.1.1">RFC 9700 §2.1.1 — Public clients MUST use PKCE</a>
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
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
        redirectUris.forEach(builder::redirectUri);
        return builder
                .scope("openid")
                .scope("profile")
                .clientSettings(buildClientSettings())
                .tokenSettings(buildTokenSettings())
                .build();
    }

    /**
     * {@code requireProofKey(true)} is mandatory for a public client (RFC 9700 §2.1.1)
     * and protects against the PKCE downgrade attack (SAS how-to). Consent is required,
     * matching the confidential client's explicit setting (INV-2).
     */
    private static ClientSettings buildClientSettings() {
        return ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build();
    }

    /**
     * No refresh-token settings on purpose: the refresh grant is absent and SAS "will not
     * issue refresh tokens for a public client" (gh-297) — the access token (900s) plus
     * the authorization-server session is the official pattern for this client class.
     */
    private static TokenSettings buildTokenSettings() {
        return TokenSettings.builder()
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
