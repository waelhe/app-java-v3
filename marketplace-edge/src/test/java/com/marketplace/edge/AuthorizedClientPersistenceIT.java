package com.marketplace.edge;

import java.time.Instant;

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

}
