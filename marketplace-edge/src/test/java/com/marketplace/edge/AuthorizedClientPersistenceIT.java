package com.marketplace.edge;

import java.time.Instant;
import java.util.TimeZone;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped migration (the official PostgreSQL schema of
 * {@code JdbcOAuth2AuthorizedClientService}) supports the official service.
 * The refresh token is built with the two-argument constructor, exactly as the
 * framework builds it from a token response (no expiry), and the access token
 * is already expired: the entry must survive so that the refresh_token grant
 * can still be used.
 *
 * <p>The second test pins the timestamptz adoption (CodeRabbit round-1,
 * citing the PostgreSQL datatype-datetime reference): a JVM that writes the
 * row under one default zone and a JVM that reads it under another must see
 * the SAME instants — with plain {@code timestamp} the wall clock shifts by
 * the zone difference and the token's expiry moves with it.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthorizedClientPersistenceIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void savedClientWithExpiredAccessTokenStillCarriesItsRefreshToken() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        ClientRegistration registration = ClientRegistration.withRegistrationId("edge")
            .clientId("edge")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/edge")
            .authorizationUri("http://localhost/oauth2/authorize")
            .tokenUri("http://localhost/oauth2/token")
            .build();
        JdbcOAuth2AuthorizedClientService service = new JdbcOAuth2AuthorizedClientService(
                new JdbcTemplate(dataSource), new InMemoryClientRegistrationRepository(registration));
        Instant issuedAt = Instant.now().minusSeconds(3600);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access",
                issuedAt, issuedAt.plusSeconds(900));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken("refresh", issuedAt);

        service.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", accessToken, refreshToken),
                new TestingAuthenticationToken("user", "n/a"));

        OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("edge", "user");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh");
        assertThat(loaded.getAccessToken().getExpiresAt()).isBefore(Instant.now());
    }


    /**
     * The cross-zone round trip (the timestamptz adoption's regression
     * test): the row is WRITTEN with the JVM default zone at UTC+03
     * (Asia/Riyadh) and READ with the JVM default zone at UTC-08
     * (America/Los_Angeles) — eleven hours apart. Every token instant
     * must survive the trip unchanged: the issued-at, the expires-at,
     * and the refresh token's issued-at.
     */
    @Test
    void tokenTimesSurviveAcrossJvmTimeZoneChanges() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"));
            Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            ClientRegistration registration = ClientRegistration.withRegistrationId("edge")
                .clientId("edge")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/edge")
                .authorizationUri("http://localhost/oauth2/authorize")
                .tokenUri("http://localhost/oauth2/token")
                .build();
            JdbcOAuth2AuthorizedClientService writer = new JdbcOAuth2AuthorizedClientService(
                    new JdbcTemplate(dataSource), new InMemoryClientRegistrationRepository(registration));
            Instant issuedAt = Instant.parse("2026-10-08T09:41:00Z");
            Instant expiresAt = Instant.parse("2026-10-08T10:11:00Z");
            writer.saveAuthorizedClient(
                    new OAuth2AuthorizedClient(registration, "zone-user",
                            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "zaccess",
                                    issuedAt, expiresAt),
                            new OAuth2RefreshToken("zrefresh", issuedAt)),
                    new TestingAuthenticationToken("zone-user", "n/a"));

            // the reading JVM runs eleven wall-clock hours behind
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            JdbcOAuth2AuthorizedClientService reader = new JdbcOAuth2AuthorizedClientService(
                    new JdbcTemplate(dataSource), new InMemoryClientRegistrationRepository(registration));
            OAuth2AuthorizedClient loaded = reader.loadAuthorizedClient("edge", "zone-user");
            assertThat(loaded).isNotNull();
            assertThat(loaded.getAccessToken().getIssuedAt()).isEqualTo(issuedAt);
            assertThat(loaded.getAccessToken().getExpiresAt()).isEqualTo(expiresAt);
            assertThat(loaded.getRefreshToken().getIssuedAt()).isEqualTo(issuedAt);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
