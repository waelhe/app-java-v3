package com.marketplace.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.util.StringUtils;

/**
 * Durable, shared authorized-client store for the edge BFF — closes the last
 * statefulness gap between the shared-session design and the TokenRelay
 * filter.
 *
 * <p>Official basis — Spring Cloud Gateway Server MVC reference, "TokenRelay
 * Filter" (spring-cloud-gateway-server-webmvc/filters/tokenrelay.html):
 * "The default implementation used by the Token Relay filter uses an
 * in-memory data store. You will need to provide your own implementation
 * {@code OAuth2AuthorizedClientService} if you need a more robust solution."
 * An in-memory store contradicts this module's own design (application.yml:
 * sessions are shared via Redis precisely so any instance can serve any
 * request): a surviving Redis session paired with a lost in-memory
 * authorized client leaves the relay without a token after any restart,
 * redeploy, or instance hop. The official remedy — provide an
 * {@code OAuth2AuthorizedClientService} — is implemented here against the
 * Redis that is already the session backend, so the token lifecycle rides
 * the same managed infrastructure (zero new dependencies, zero new secrets,
 * zero manual steps).
 *
 * <p>Bean wiring follows Spring Boot's own auto-configuration contract:
 * declaring these beans backs off Boot's in-memory defaults via
 * {@code @ConditionalOnMissingBean} (Boot API), and the gateway's
 * {@code DefaultOAuth2AuthorizedClientManager} (auto-configured from these
 * beans) transparently persists refreshed tokens back through the same
 * service — automatic token lifecycle management end to end.
 */
@Configuration
class EdgeOAuth2AuthorizedClientStoreConfig {

    @Bean
    OAuth2AuthorizedClientService oAuth2AuthorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository,
            StringRedisTemplate redis,
            @Value("${spring.session.timeout:30m}") Duration sessionTimeout) {
        return new RedisOAuth2AuthorizedClientService(clientRegistrationRepository, redis, sessionTimeout);
    }

    @Bean
    OAuth2AuthorizedClientRepository oAuth2AuthorizedClientRepository(
            OAuth2AuthorizedClientService authorizedClientService) {
        // The same repository type Boot itself auto-configures for the
        // servlet stack (OAuth2ClientAutoConfiguration), only backed by the
        // shared store instead of the in-memory default.
        return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
    }
}

/**
 * Redis-backed {@link OAuth2AuthorizedClientService}. ClientRegistration
 * metadata is NOT duplicated into Redis: registrations are static
 * configuration resolved from the {@link ClientRegistrationRepository};
 * only the token material (the per-user state) is stored.
 */
class RedisOAuth2AuthorizedClientService implements OAuth2AuthorizedClientService {

    static final String KEY_PREFIX = "marketplace:edge:authorized-client:";

    private static final String F_TOKEN_TYPE = "tokenType";
    private static final String F_TOKEN_VALUE = "tokenValue";
    private static final String F_ISSUED_AT = "issuedAt";
    private static final String F_EXPIRES_AT = "expiresAt";
    private static final String F_SCOPES = "scopes";
    private static final String F_REFRESH_VALUE = "refreshTokenValue";
    private static final String F_REFRESH_ISSUED_AT = "refreshTokenIssuedAt";
    private static final String F_REFRESH_EXPIRES_AT = "refreshTokenExpiresAt";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final StringRedisTemplate redis;
    private final Duration fallbackTtl;

    RedisOAuth2AuthorizedClientService(ClientRegistrationRepository clientRegistrationRepository,
            StringRedisTemplate redis, Duration fallbackTtl) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.redis = redis;
        this.fallbackTtl = fallbackTtl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
            String principalName) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(clientRegistrationId, principalName));
        if (raw.isEmpty()) {
            return null;
        }
        ClientRegistration registration = clientRegistrationRepository
                .findByRegistrationId(clientRegistrationId);
        if (registration == null) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        raw.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));

        Set<String> scopes = StringUtils.hasText(fields.get(F_SCOPES))
                ? new HashSet<>(Arrays.asList(fields.get(F_SCOPES).split(",")))
                : Set.of();
        // The edge relays only bearer tokens (OAuth2 access tokens over
        // Authorization: Bearer) — the only type this client ever stores.
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                fields.get(F_TOKEN_VALUE),
                instant(fields.get(F_ISSUED_AT)), instant(fields.get(F_EXPIRES_AT)), scopes);
        OAuth2RefreshToken refreshToken = StringUtils.hasText(fields.get(F_REFRESH_VALUE))
                ? new OAuth2RefreshToken(fields.get(F_REFRESH_VALUE),
                        instant(fields.get(F_REFRESH_ISSUED_AT)), instant(fields.get(F_REFRESH_EXPIRES_AT)))
                : null;
        return (T) new OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken);
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) {
        String principalName = authorizedClient.getPrincipalName();
        String key = key(authorizedClient.getClientRegistration().getRegistrationId(), principalName);

        Duration ttl = ttlOf(authorizedClient);
        if (ttl.isNegative() || ttl.isZero()) {
            // Everything already expired: persist nothing, drop any residue.
            redis.delete(key);
            return;
        }
        Map<String, String> fields = new HashMap<>();
        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
        fields.put(F_TOKEN_TYPE, accessToken.getTokenType().getValue());
        fields.put(F_TOKEN_VALUE, accessToken.getTokenValue());
        if (accessToken.getIssuedAt() != null) {
            fields.put(F_ISSUED_AT, accessToken.getIssuedAt().toString());
        }
        if (accessToken.getExpiresAt() != null) {
            fields.put(F_EXPIRES_AT, accessToken.getExpiresAt().toString());
        }
        if (accessToken.getScopes() != null && !accessToken.getScopes().isEmpty()) {
            fields.put(F_SCOPES, String.join(",", accessToken.getScopes()));
        }
        OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
        if (refreshToken != null) {
            fields.put(F_REFRESH_VALUE, refreshToken.getTokenValue());
            if (refreshToken.getIssuedAt() != null) {
                fields.put(F_REFRESH_ISSUED_AT, refreshToken.getIssuedAt().toString());
            }
            if (refreshToken.getExpiresAt() != null) {
                fields.put(F_REFRESH_EXPIRES_AT, refreshToken.getExpiresAt().toString());
            }
        }
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
        redis.delete(key(clientRegistrationId, principalName));
    }

    private static String key(String clientRegistrationId, String principalName) {
        return KEY_PREFIX + clientRegistrationId + ":" + principalName;
    }

    private static Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    /**
     * Entry lifetime mirrors the token material itself: the refresh token's
     * expiry when known (the entry is useful exactly as long as it can still
     * mint access tokens), else the access token's expiry, else the session
     * timeout (the entry never outlives the session that owns it).
     */
    private Duration ttlOf(OAuth2AuthorizedClient client) {
        Instant now = Instant.now();
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        if (refreshToken != null && refreshToken.getExpiresAt() != null) {
            return Duration.between(now, refreshToken.getExpiresAt());
        }
        if (client.getAccessToken().getExpiresAt() != null) {
            return Duration.between(now, client.getAccessToken().getExpiresAt());
        }
        return fallbackTtl;
    }
}
