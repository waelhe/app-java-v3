package com.marketplace.edge;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S2/S3 slice for the edge BFF relay (plan Task 4): anonymous {@code /api/**}
 * must 302 to the SAS login chain, and an authenticated call must arrive at the
 * backend carrying the relayed Bearer token (asserted via WireMock capture).
 *
 * <p>No live SAS in CI: the WireMock backend serves BOTH the OIDC discovery
 * document (so Boot's issuer-uri startup resolution succeeds against a stub)
 * and the backend {@code /api/**} surface. Backend/token URLs are injected via
 * {@code @DynamicPropertySource} before the context boots; the token itself is
 * a stubbed {@code OAuth2AuthorizedClientManager} bean in test scope (plan
 * Task 4 Step 1), so no authorization-code round-trip ever runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// No @Testcontainers here by design: this IT uses zero containers (WireMock is
// in-process, no Redis is wired yet — Task 5). The house @Testcontainers/
// disabledWithoutDocker gate skips the whole class wherever the Docker daemon
// is down (measured locally), which would make a containerless test
// unverifiable. Container-backed session tests (Task 5) will carry the gate.
class EdgeRelayIT {

    static final WireMockServer backend = new WireMockServer(options().dynamicPort());

    static {
        backend.start();
        String base = backend.baseUrl();
        backend.stubFor(WireMock.get(urlEqualTo("/.well-known/openid-configuration"))
                .willReturn(okJson("""
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/oauth2/authorize",
                          "token_endpoint": "%s/oauth2/token",
                          "jwks_uri": "%s/oauth2/jwks",
                          "userinfo_endpoint": "%s/userinfo",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(base, base, base, base, base))));
        backend.stubFor(WireMock.get(urlEqualTo("/api/v1/listings"))
                .willReturn(okJson("{\"items\":[]}")));
    }

    @DynamicPropertySource
    static void backendProps(DynamicPropertyRegistry registry) {
        registry.add("EDGE_BACKEND_URL", backend::baseUrl);
        registry.add("AUTH_SERVER_ISSUER", backend::baseUrl);
        registry.add("EDGE_CLIENT_SECRET", () -> "test-secret");
        // Tests run with zero live infrastructure: the Redis session backend
        // engages purely by RedisConnectionFactory bean presence
        // (SessionDataRedisAutoConfiguration, ConditionalOnBean — Boot 4.1 has
        // no store-type property: SessionProperties exposes only timeout +
        // servlet/*, verified by javap). Excluding it here restores the
        // in-memory default for tests only; prod keeps the starter untouched.
        // (Placed here rather than application-test.yml: the yml-listed
        // exclusion was measured as not applied, while the identical key via
        // env/DynamicPropertySource excludes correctly.)
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration");
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @Autowired
    MockMvc mockMvc;

    // Test-scope stub of the token source: the TokenRelay filter resolves its
    // OAuth2AuthorizedClientManager bean from the context (Gateway Server MVC
    // TokenRelay reference), so the mock feeds the relay a canned token and no
    // live SAS round-trip is needed.
    @MockitoBean
    OAuth2AuthorizedClientManager authorizedClientManager;

    // Test-scope transport pin (prod untouched): Gateway 5.0.3 builds its proxy
    // RestClient from Boot's RestClient.Builder via
    // GatewayServerMvcAutoConfiguration#gatewayRestClientCustomizer, which
    // consumes an optional ClientHttpRequestFactory bean (verified by javap on
    // spring-cloud-gateway-server-webmvc-5.0.3.jar). The default JDK HTTP_2
    // client emits an h2c Upgrade + chunked placeholder on bodyless requests;
    // WireMock's plain-HTTP connector never completes the upgrade, so the
    // client EOFs (measured: request matched + Bearer injected, then
    // "EOF reached while reading"). The production backend (Tomcat 11) speaks
    // h2c properly, so this is a test-backend limitation, not a route defect:
    // pinning the classic HttpURLConnection factory keeps routes, TokenRelay,
    // and header propagation byte-identical while speaking plain HTTP/1.1.
    @TestConfiguration
    static class Http11TestTransport {
        @Bean
        ClientHttpRequestFactory testClientHttpRequestFactory() {
            return new SimpleClientHttpRequestFactory();
        }
    }

    @Test
    void anonymousApiCallRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/edge")));
    }

    @Test
    void authenticatedApiCallRelaysBearerToBackend() throws Exception {
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "test-relayed-token",
                Instant.now(), Instant.now().plusSeconds(300));
        ClientRegistration registration = ClientRegistration.withRegistrationId("edge")
                .clientId("edge")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/edge")
                .authorizationUri(backend.baseUrl() + "/oauth2/authorize")
                .tokenUri(backend.baseUrl() + "/oauth2/token")
                .build();
        when(authorizedClientManager.authorize(any()))
                .thenReturn(new OAuth2AuthorizedClient(registration, "user", token));

        mockMvc.perform(get("/api/v1/listings").with(oauth2Login()))
                .andExpect(status().isOk());

        backend.verify(WireMock.getRequestedFor(urlEqualTo("/api/v1/listings"))
                .withHeader("Authorization", equalTo("Bearer test-relayed-token")));
    }
}
