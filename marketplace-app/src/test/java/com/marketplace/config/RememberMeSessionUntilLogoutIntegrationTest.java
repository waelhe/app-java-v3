package com.marketplace.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.observation.SecurityObservationSettings;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The end-to-end proof of the Spring Security 7.1.1 compliance wave's
 * session-policy and logout items, on the REAL application context (Postgres
 * + Redis via containers, the real filter chains, the real Spring Session
 * Redis backend):
 *
 * <ul>
 *   <li><b>G-2 — "sessions until logout" on the documented official path</b>:
 *       a real form login through the default chain must (a) set the session
 *       cookie at {@code Max-Age=2147483647} — the Spring Session reference's
 *       "Ensures that the session cookie expires at Integer.MAX_VALUE" — via
 *       the automatic serializer arming the
 *       {@code SpringSessionRememberMeServices} bean's presence triggers, and
 *       (b) raise the stored session to the documented thirty-days
 *       {@code maxInactiveInterval} (2592000 seconds), read back through the
 *       session repository (the Redis TTL equals this value by Spring
 *       Session's own construction — the session record is stored with the
 *       expiry).</li>
 *   <li><b>C-8 — the documented logout cleanup</b>: a secure POST /logout
 *       must answer the {@code Clear-Site-Data: "cookies"} header (the
 *       reference's "Using Clear-Site-Data to Clear Cookies" recipe), and the
 *       session must be gone from the repository afterwards — logout really
 *       ends the session, the "until logout" half of the policy.</li>
 *   <li><b>G-4 — observability on the documented default</b>: the context
 *       exposes an {@code ObservationRegistry} (Boot actuator's automatic
 *       management) and declares NO {@code SecurityObservationSettings}
 *       restriction bean, which is the reference's "all observations on"
 *       default — an intentional, pinned state, not an accident.</li>
 * </ul>
 *
 * <p>CSRF flows the honest way (no post-processor): the login page is fetched
 * and its {@code _csrf} input parsed for both POSTs — the same black-box
 * shape as {@code AuthorizationServerLoginGateIntegrationTest}. The user is
 * seeded through the {@code UserDetailsManager} bean ({@code {noop}} test
 * password, the established IT idiom that keeps gitleaks quiet).</p>
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql,classpath:sql/init/auth_effective_authorities_view.sql",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RememberMeSessionUntilLogoutIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern (this testcontainers version ships a non-generic PostgreSQLContainer).
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String USERNAME = "it-remember-me-user";
    private static final String PASSWORD = "it-remember-me-password";

    private static final Pattern CSRF_INPUT =
            Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED =
            Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    void seedUser() {
        if (!userDetailsManager.userExists(USERNAME)) {
            userDetailsManager.createUser(User.withUsername(USERNAME)
                    .password("{noop}" + PASSWORD)
                    .roles("USER")
                    .build());
        }
    }

    @Test
    void formLoginRaisesTheSessionToThirtyDaysAndTheCookieToIntegerMaxValue() throws Exception {
        MvcResult page = loginPageResult(null);
        String loginCsrf = csrfOf(page.getResponse().getContentAsString());

        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD)
                        .param("_csrf", loginCsrf)
                        .cookie(page.getResponse().getCookies()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // (a) The documented cookie leg: "Ensures that the session cookie
        // expires at Integer.MAX_VALUE" — the automatic serializer arming
        // (the SpringSessionRememberMeServices bean's presence) wrote it.
        assertThat(login.getResponse().getHeaders("Set-Cookie"))
                .as("the SESSION cookie carries Max-Age=2147483647 after a remember-me login")
                .anyMatch(header -> header.contains("Max-Age=2147483647"));

        // (b) The documented session leg: "Changes the session expiration
        // length" — the stored session reads back the class's official
        // thirty-days default (the Redis record's TTL equals it by Spring
        // Session's own construction).
        //
        // The cookie VALUE is the base64-encoded session id — the default
        // DefaultCookieSerializer writes it encoded (useBase64Encoding=true,
        // source-verified on spring-session-core 4.1.1) and decodes it on the
        // way IN (readCookieValues -> base64Decode). A browser round-trips
        // the encoded value untouched, so the repository key — the RAW id —
        // is only reachable after the same decode the serializer performs.
        // Looking the encoded value up (the first CI run's measurement)
        // queries a key that never exists: findById answers null for a
        // session that is alive and well in Redis.
        String sessionCookie = cookieValue(login.getResponse(), "SESSION");
        assertThat(sessionCookie).as("a session was established").isNotBlank();
        String sessionId = rawSessionId(sessionCookie);
        Session stored = sessionRepository.findById(sessionId);
        assertThat(stored).as("the session is in the Redis-backed repository").isNotNull();
        assertThat(stored.getMaxInactiveInterval().toSeconds())
                .as("the documented THIRTY_DAYS_SECONDS default")
                .isEqualTo(2592000L);
    }

    @Test
    void logoutClearsSiteDataCookiesAndEndsTheSession() throws Exception {
        // The cookie round-trips its ENCODED value (exactly what a browser
        // sends back — the serializer decodes it on read), while the
        // repository lookup below asserts on the DECODED id: the previous
        // form looked the encoded value up, so the "session is gone"
        // assertion passed VACUOUSLY — the wrong key is absent whether or
        // not the logout actually invalidated the session.
        String sessionCookie = loginForSession();
        String sessionId = rawSessionId(sessionCookie);
        String logoutCsrf = csrfOf(loginPage(sessionCookie));

        MvcResult logout = mockMvc.perform(post("/logout")
                        .cookie(new Cookie("SESSION", sessionCookie))
                        .param("_csrf", logoutCsrf)
                        .secure(true))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // The documented cleanup header (secure requests only — the writer's
        // own SecureRequestMatcher).
        assertThat(logout.getResponse().getHeader("Clear-Site-Data"))
                .as("the reference's Clear-Site-Data COOKIES recipe fires on the secure logout")
                .isEqualTo("\"cookies\"");

        // "Until logout" means the logout actually ends the session: the
        // stored record is gone — asserted on the REAL repository key.
        assertThat(sessionRepository.findById(sessionId))
                .as("the session is invalidated by the default logout machinery")
                .isNull();
    }

    @Test
    void securityObservabilityRunsOnTheDocumentedDefaultAll() {
        // G-4: "When an ObservationRegistry bean is present, Spring Security
        // creates traces for: the filter chain, the AuthenticationManager,
        // and the AuthorizationManager" — and the only way to narrow that is
        // a SecurityObservationSettings bean, which this application does
        // not declare. The default-all state, asserted rather than assumed.
        assertThat(context.getBeansOfType(ObservationRegistry.class))
                .as("Boot actuator's automatic ObservationRegistry is present")
                .isNotEmpty();
        assertThat(context.getBeansOfType(SecurityObservationSettings.class))
                .as("no observation restriction bean exists — the documented default observes everything")
                .isEmpty();
    }

    /**
     * The raw repository key of the session cookie's value — the exact
     * decode the default serializer applies on every inbound request
     * (DefaultCookieSerializer.base64Decode: RFC 4648 basic decoder, UTF-8).
     * Mirrored here because the repository keys sessions by the RAW id,
     * while the cookie carries the encoded form a browser would return.
     */
    private static String rawSessionId(String sessionCookieValue) {
        return new String(java.util.Base64.getDecoder().decode(sessionCookieValue));
    }

    /**
     * Performs the honest form login and returns the established session's
     * COOKIE value (the encoded form — pass it to requests unchanged, the
     * way a browser would).
     */
    private String loginForSession() throws Exception {
        MvcResult page = loginPageResult(null);
        String loginCsrf = csrfOf(page.getResponse().getContentAsString());
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD)
                        .param("_csrf", loginCsrf)
                        .cookie(page.getResponse().getCookies()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return cookieValue(login.getResponse(), "SESSION");
    }

    /**
     * GETs the framework login page (with the session when present) so its
     * CSRF token can be posted honestly — the black-box IT idiom.
     */
    private String loginPage(String sessionId) throws Exception {
        return loginPageResult(sessionId).getResponse().getContentAsString();
    }

    /**
     * The full login-page result. The app's CsrfFilter is active on /login
     * and keeps the token in the session this very GET issues, so the
     * follow-up POST must carry this response's cookies alongside the
     * token parameter — dropping them left the repository without the
     * session and the filter answered 403 (measured on this IT's first CI
     * run; locally it never executed, being docker-gated).
     */
    private MvcResult loginPageResult(String sessionId) throws Exception {
        var request = get("/login");
        if (sessionId != null) {
            request.cookie(new Cookie("SESSION", sessionId));
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String csrfOf(String loginPageHtml) {
        Matcher forward = CSRF_INPUT.matcher(loginPageHtml);
        if (forward.find()) {
            return forward.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(loginPageHtml);
        if (reversed.find()) {
            return reversed.group(1);
        }
        throw new IllegalStateException("no _csrf input found on the login page");
    }

    private static String cookieValue(MockHttpServletResponse response, String name) {
        return java.util.Arrays.stream(response.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
