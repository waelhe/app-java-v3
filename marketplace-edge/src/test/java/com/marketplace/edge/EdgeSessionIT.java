package com.marketplace.edge;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S2/S3 slice for BFF sessions + CSRF (plan Task 5) — fully black-box: no
 * authentication mocks, the real login redirect chain creates the session.
 *
 * <p>No live SAS in CI: the in-process WireMock serves the OIDC discovery
 * document (Boot's issuer-uri startup resolution succeeds against a stub), so
 * the client-registration repository is REAL here. No live Redis either: the
 * Redis session backend engages purely by starter presence (Boot 4.1
 * {@code ConditionalOnBean} — no store-type property exists), so this class
 * excludes {@code SessionDataRedisAutoConfiguration} and enables an explicit
 * in-memory backend via Spring Session's own {@code @EnableSpringHttpSession}
 * (same {@code SessionRepositoryFilter} mechanics as production, only the
 * repository differs). The production backend
 * selection itself is pinned by {@code EdgeSessionBackendSelectionTest} below
 * (no HTTP, no server: bean wiring is lazy). Cross-instance sharing is a pure
 * function of that backend and is covered where Docker exists (staging/CI).
 */
// No @Testcontainers by design (see EdgeRelayIT): zero containers used.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EdgeSessionIT {

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
                          "end_session_endpoint": "%s/connect/logout",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(base, base, base, base, base, base))));
    }

    @DynamicPropertySource
    static void sessionTestProps(DynamicPropertyRegistry registry) {
        registry.add("EDGE_BACKEND_URL", backend::baseUrl);
        registry.add("AUTH_SERVER_ISSUER", backend::baseUrl);
        registry.add("EDGE_CLIENT_SECRET", () -> "test-secret");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration");
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @Autowired
    MockMvc mockMvc;

    // The REAL, property-built registration repository (its discovery
    // metadata is served by the WireMock stub above, including the
    // end_session_endpoint the C-7 logout handler reads).
    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    // Explicit in-memory Spring Session backend for HTTP tests (see class
    // comment): Boot 4.1 has no session store-type property and no in-memory
    // default bean — without this, requests fall back to container sessions
    // and no SESSION cookie is ever issued (measured).
    // {@code @EnableSpringHttpSession} wires the filter mechanics; the
    // repository itself is an explicit bean (the annotation only consumes it).
    @TestConfiguration
    @EnableSpringHttpSession
    static class InMemoryTestSessions {
        @Bean
        SessionRepository<org.springframework.session.MapSession> sessionRepository() {
            return new MapSessionRepository(new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    @Test
    void authorizationRedirectCreatesSession() throws Exception {
        // Real login flow, no mocks: the authorization redirect stores the
        // OAuth2 authorization request in the HTTP session, so the 302 must
        // carry a SESSION cookie — proof the session mechanism is live.
        mockMvc.perform(get("/oauth2/authorization/edge"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorize")))
                .andExpect(cookie().exists("SESSION"));
    }

    @Test
    void anonymousHealthNeverEntersLoginFlow() throws Exception {
        // Orchestrator probes carry no credentials: health must answer (200
        // when all contributors up, 503 otherwise) and must NEVER 302 to the
        // OAuth2 login chain. Adopted from CodeRabbit r1 (Major, stability);
        // same idiom as the main app SecurityConfig (GET /actuator/health/**
        // permitAll).
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("anonymous health must not redirect to login")
                .isNotEqualTo(302);
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        // No global CSRF switch-off exists anywhere (plan forbids it): an
        // unsafe method without a token must 403, never reach routing —
        // even anonymous (CsrfFilter runs before authorization).
        mockMvc.perform(post("/edge-session-probe"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithCsrfTokenPassesCsrf() throws Exception {
        // Real Angular bootstrap flow: the 403 above loads the token and the
        // spa() recipe persists it as a readable XSRF-TOKEN cookie; replay it
        // as X-XSRF-TOKEN. 302 (login required) instead of 403 proves CSRF
        // passed and only authentication is missing.
        MvcResult rejected = mockMvc.perform(post("/edge-session-probe"))
                .andExpect(status().isForbidden())
                .andReturn();
        String xsrf = cookieValue(rejected, "XSRF-TOKEN");
        assertThat(xsrf).as("XSRF-TOKEN cookie expected (spa recipe)").isNotBlank();

        mockMvc.perform(post("/edge-session-probe")
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/edge")));
    }

    @Test
    void oidcLogoutBuildsTheDocumentedRpInitiatedLogoutRedirect() throws Exception {
        // C-7 (the Spring Security 7.1.1 compliance wave): the reference says
        // an OIDC client "should configure OidcClientInitiatedLogoutSuccessHandler,
        // which implements RP-Initiated Logout". The handler is built by the
        // production factory against the REAL client registration repository
        // (Boot's property-built one, its discovery metadata served by the
        // stub above with end_session_endpoint) and driven through its public
        // onLogoutSuccess contract with an OIDC-authenticated principal: the
        // redirect must target the provider's end-session endpoint carrying
        // the id_token_hint and the {baseUrl}-expanded post_logout_redirect_uri
        // — the documented RP-initiated logout request shape.
        LogoutSuccessHandler handler =
                new EdgeSecurityConfig().oidcLogoutSuccessHandler(clientRegistrationRepository);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8081);

        OidcIdToken idToken = new OidcIdToken(
                "test-id-token-value",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(300),
                Map.of(IdTokenClaimNames.SUB, "it-logout-user"));
        OidcUser principal = new DefaultOidcUser(
                AuthorityUtils.createAuthorityList("SCOPE_openid"), idToken);
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, AuthorityUtils.createAuthorityList("SCOPE_openid"), "edge");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onLogoutSuccess(request, response, authentication);

        String redirect = response.getRedirectedUrl();
        assertThat(redirect).as("the redirect targets the discovered end-session endpoint")
                .startsWith(backend.baseUrl() + "/connect/logout");
        assertThat(redirect).as("the id_token_hint carries the logged-in user's ID token")
                .contains("id_token_hint=test-id-token-value");
        assertThat(redirect).as("the post-logout landing is the {baseUrl}-expanded value")
                .contains("post_logout_redirect_uri=http://localhost:8081");
    }

    private static String cookieValue(MvcResult result, String name) {
        return java.util.Arrays.stream(result.getResponse().getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}

/**
 * Production backend selection, no HTTP and no server: with the Redis starter
 * on the classpath and no test exclusion, the {@code SessionRepository} bean
 * must be the Redis one (lazy wiring — needs no live connection to assert).
 */
@SpringBootTest
@ActiveProfiles("test")
class EdgeSessionBackendSelectionTest {

    @MockitoBean
    ClientRegistrationRepository clientRegistrationRepository;

    // Declared so a missing Redis bean fails compilation of intent, not just
    // the assertion: the test classpath must see the production backend type.
    @SuppressWarnings("unused")
    private static final Class<?> BACKEND_WITNESS = RedisSessionRepository.class;

    @Autowired
    ApplicationContext context;

    @Test
    void productionSessionBackendIsRedis() {
        assertThat(context.getBean(SessionRepository.class))
                .as("prod session backend must be Redis-selected by starter presence")
                .isInstanceOf(RedisSessionRepository.class);
    }
}
