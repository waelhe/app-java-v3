package com.marketplace.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Roundtrip unit tests for {@link RedisOAuth2AuthorizedClientService} against
 * an in-memory fake of the Redis hash surface (same shape as the real
 * {@code opsForHash} contract: entries/putAll/delete/expire). No server, no
 * containers — pure store semantics.
 */
class RedisOAuth2AuthorizedClientServiceTest {

    private final Map<String, Map<Object, Object>> hashes = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();

    private ClientRegistrationRepository registrations;
    private RedisOAuth2AuthorizedClientService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hashes.clear();
        ttls.clear();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> ops = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(ops);
        lenient().when(ops.entries(anyString())).thenAnswer(inv -> hashes.getOrDefault(inv.getArgument(0), Map.of()));
        lenient().doAnswer(inv -> {
            hashes.computeIfAbsent(inv.getArgument(0), k -> new HashMap<>()).putAll(inv.getArgument(1));
            return null;
        }).when(ops).putAll(anyString(), anyMap());
        lenient().when(redis.expire(anyString(), any(Duration.class))).thenAnswer((InvocationOnMock inv) -> {
            ttls.put(inv.getArgument(0), inv.getArgument(1));
            return true;
        });
        lenient().when(redis.delete(anyString())).thenAnswer((InvocationOnMock inv) -> {
            hashes.remove(inv.getArgument(0));
            ttls.remove(inv.getArgument(0));
            return true;
        });

        registrations = mock(ClientRegistrationRepository.class);
        when(registrations.findByRegistrationId("edge")).thenReturn(registration());

        service = new RedisOAuth2AuthorizedClientService(registrations, redis, Duration.ofMinutes(30));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("edge")
                .clientId("edge")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/edge")
                .authorizationUri("https://auth.example/oauth2/authorize")
                .tokenUri("https://auth.example/oauth2/token")
                .build();
    }

    private static OAuth2AuthorizedClient client(String principal, Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "access-token-value", accessExpiresAt.minusSeconds(600), accessExpiresAt);
        OAuth2RefreshToken refreshToken = refreshExpiresAt != null
                ? new OAuth2RefreshToken("refresh-token-value", Instant.now(), refreshExpiresAt)
                : null;
        return new OAuth2AuthorizedClient(registration(), principal, accessToken, refreshToken);
    }

    @Test
    void saveThenLoadRoundtripsTokens() {
        Instant accessExp = Instant.now().plusSeconds(300);
        Instant refreshExp = Instant.now().plusSeconds(3600);
        service.saveAuthorizedClient(client("user-1", accessExp, refreshExp), null);

        OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("edge", "user-1");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getPrincipalName()).isEqualTo("user-1");
        assertThat(loaded.getClientRegistration().getRegistrationId()).isEqualTo("edge");
        assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("access-token-value");
        assertThat(loaded.getAccessToken().getExpiresAt()).isEqualTo(accessExp);
        assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh-token-value");
        assertThat(loaded.getRefreshToken().getExpiresAt()).isEqualTo(refreshExp);
    }

    @Test
    void ttlFollowsRefreshTokenExpiry() {
        Instant refreshExp = Instant.now().plusSeconds(3600);
        service.saveAuthorizedClient(client("user-1", Instant.now().plusSeconds(300), refreshExp), null);

        Duration ttl = ttls.get(RedisOAuth2AuthorizedClientService.KEY_PREFIX + "edge:user-1");
        assertThat(ttl.toMillis()).isBetween(3_500_000L, 3_600_000L);
    }

    @Test
    void ttlFallsBackToAccessTokenExpiryWithoutRefreshToken() {
        service.saveAuthorizedClient(client("user-1", Instant.now().plusSeconds(600), null), null);

        Duration ttl = ttls.get(RedisOAuth2AuthorizedClientService.KEY_PREFIX + "edge:user-1");
        assertThat(ttl.toMillis()).isBetween(500_000L, 600_000L);
    }

    @Test
    void expiredClientIsNotPersisted() {
        service.saveAuthorizedClient(client("user-1", Instant.now().minusSeconds(1), null), null);

        assertThat((Object) service.loadAuthorizedClient("edge", "user-1")).isNull();
    }

    @Test
    void removeDeletesTheEntry() {
        service.saveAuthorizedClient(client("user-1", Instant.now().plusSeconds(300), null), null);
        service.removeAuthorizedClient("edge", "user-1");

        assertThat((Object) service.loadAuthorizedClient("edge", "user-1")).isNull();
    }

    @Test
    void unknownPrincipalLoadsNull() {
        assertThat((Object) service.loadAuthorizedClient("edge", "nobody")).isNull();
    }
}
