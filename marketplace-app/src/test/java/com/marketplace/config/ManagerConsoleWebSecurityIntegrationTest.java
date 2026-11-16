package com.marketplace.config;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The manager console's web security contract — the officially guided
 * server-rendered experience secured the securing-web way: the default
 * form-login chain owns /console/** (formLogin + sessions + CSRF at its
 * documented default + remember-me + the Clear-Site-Data logout), and the
 * authorization rule is hasRole('ADMIN') — the same vocabulary every
 * /api/v1/admin surface carries.
 *
 * <p><b>The three contracts this integration pins:</b></p>
 * <ol>
 *   <li><b>Anonymous → the login redirect</b> (the form-login chain's own
 *   entry point — the securing-web behavior, not the resource chain's
 *   problem+json 401), pinned to the relative URL the entry point actually
 *   sends.</li>
 *   <li><b>Authenticated non-admin → 403</b> (the role gate).</li>
 *   <li><b>Authenticated admin → the rendered console</b> — the Arabic
 *   tri-partite page with the flip form carrying the CSRF token
 *   (Thymeleaf's own form integration), and the flip POST honoring CSRF
 *   (403 without it, the post/redirect/get 302 with it).</li>
 * </ol>
 *
 * <p><b>Authentication is the real thing, not a post-processor shortcut.</b>
 * {@code @WithMockUser}'s TestSecurityContextHolder never reaches MockMvc
 * requests in this assembly (every call arrived anonymous and left as a
 * 302 to /login), so this suite uses the established black-box IT idiom
 * the login-gate and remember-me suites already pass with: the two
 * principals are seeded through the very JdbcUserDetailsManager the chain
 * reads ({@code {noop}} test passwords — the idiom that keeps gitleaks
 * quiet), the login is a real POST through the chain (GET the login page,
 * parse its {@code _csrf}, POST the credentials, ride the SESSION cookie
 * the login response sets), and the flip POST's CSRF token is parsed out
 * of the rendered console page's own form — the same honest token a
 * browser would submit.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagerConsoleWebSecurityIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; configuration from the IntegrationContainers factory
    static org.testcontainers.containers.GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String ADMIN_USERNAME = "it-console-admin";
    private static final String USER_USERNAME = "it-console-user";
    private static final String PASSWORD = "it-console-password";

    private static final Pattern CSRF_INPUT =
            Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED =
            Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @BeforeAll
    void seedUsers() {
        // Rows through the same JdbcUserDetailsManager the login chain
        // reads; the V188 auth_effective_authorities union carries the
        // auth_authorities rows straight through to the next login.
        if (!userDetailsManager.userExists(ADMIN_USERNAME)) {
            userDetailsManager.createUser(User.withUsername(ADMIN_USERNAME)
                    .password("{noop}" + PASSWORD)
                    .roles("ADMIN")
                    .build());
        }
        if (!userDetailsManager.userExists(USER_USERNAME)) {
            userDetailsManager.createUser(User.withUsername(USER_USERNAME)
                    .password("{noop}" + PASSWORD)
                    .roles("USER")
                    .build());
        }
    }

    @Test
    void theAnonymousCallerIsSentToTheLoginPage() throws Exception {
        // The form-login chain's documented entry point: a redirect to
        // /login, never the resource chain's problem+json 401. The entry
        // point sends the relative URL — '**/login' (an absolute-URL ant
        // pattern) can never match it, so the pin is the URL itself.
        mockMvc.perform(get("/console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void theAuthenticatedNonAdminIsDenied() throws Exception {
        String session = loginForSession(USER_USERNAME);

        mockMvc.perform(get("/console").cookie(new Cookie("SESSION", session)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAdminSeesTheRenderedConsole() throws Exception {
        String session = loginForSession(ADMIN_USERNAME);

        mockMvc.perform(get("/console").cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("لوحة مدير المنصة")))
                // the tri-partite catalog renders (the Arabic section titles)
                .andExpect(content().string(containsString("التشغيل")))
                .andExpect(content().string(containsString("المحتوى")))
                .andExpect(content().string(containsString("النظام")))
                // the flip form carries the CSRF token (Thymeleaf's own
                // integration — securing-web's documented default)
                .andExpect(content().string(containsString("_csrf")))
                // the logout form rides the chain's documented /logout
                .andExpect(content().string(containsString("/logout")));
    }

    @Test
    void theFlipHonorsCsrfAndFollowsThePostRedirectGetPattern() throws Exception {
        String session = loginForSession(ADMIN_USERNAME);

        // without the token: the chain's documented 403, never a write
        mockMvc.perform(post("/console/flags/some.flag/flip")
                        .cookie(new Cookie("SESSION", session)))
                .andExpect(status().isForbidden());

        // with the token, parsed honestly out of the rendered page the
        // form lives on (the black-box idiom — the token a browser would
        // submit): post/redirect/get back to the console home
        String consoleCsrf = csrfOf(consolePage(session));
        mockMvc.perform(post("/console/flags/some.flag/flip")
                        .cookie(new Cookie("SESSION", session))
                        .param("_csrf", consoleCsrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console"));
    }

    /**
     * The console-operations wave's own registration lever, end to end
     * through the real chain: the operator POSTs the register form (CSRF
     * honored — Thymeleaf's own integration carries the token), the row
     * lands through the SAME service the REST console writes through,
     * and the redirected board carries BOTH the confirmation banner (the
     * flash map's one-time delivery) and the newly registered row with
     * its live state — the operator SEES what the lever did.
     */
    @Test
    void theFlagRegistrationLandsAndTheRedirectedBoardShowsTheRowAndTheBanner() throws Exception {
        String session = loginForSession(ADMIN_USERNAME);
        String csrf = csrfOf(consolePage(session));

        String uniqueKey = "it.web.registered." + System.nanoTime();

        mockMvc.perform(post("/console/flags")
                        .cookie(new Cookie("SESSION", session))
                        .param("key", uniqueKey)
                        .param("description", "صف للاختبار")
                        .param("enabled", "on")
                        .param("_csrf", csrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console"));

        // the flash banner rides the redirected GET (its model BEFORE the
        // handler runs), and the board carries the row itself
        String board = consolePage(session);
        assertThat(board).contains("سُجّل العلم " + uniqueKey);
        assertThat(board).contains(uniqueKey);
        assertThat(board).contains("مفعّل");
    }

    /**
     * The planning board's Day-1 state (V214's seed) and its publish
     * lever, end to end: the seeded revision 1 renders as the current
     * planning, the operator's publish appends revision 2 through the
     * service's validating gate, and the redirected planning board shows
     * BOTH revisions with the confirmation banner naming the new number.
     * An invalid payload is refused with the banner — nothing stored.
     */
    @Test
    void thePlanningBoardSeedsRendersPublishesAndRefusesInvalidPayloads() throws Exception {
        String session = loginForSession(ADMIN_USERNAME);

        // the Day-1 seed: revision 1 is the current planning (the public
        // read answers 200 from day one — the gap V214 closed)
        String board = mockMvc.perform(get("/console/planning")
                        .cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("تخطيط الطرفية")))
                .andReturn().getResponse().getContentAsString();
        assertThat(board).contains("\"URGENT\"");
        assertThat(board).contains("بذرة اليوم-واحد");

        String csrf = csrfOf(board);

        // the publish: a valid payload appends revision 2
        String payload = "{\"sections\":[{\"code\":\"URGENT\",\"visible\":false,\"position\":1}]}";
        mockMvc.perform(post("/console/planning/publish")
                        .cookie(new Cookie("SESSION", session))
                        .param("payload", payload)
                        .param("note", "اختبار النشر من الويب")
                        .param("_csrf", csrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console/planning"));

        String after = mockMvc.perform(get("/console/planning")
                        .cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(after).contains("نُشرت المراجعة رقم 2");
        assertThat(after).contains("اختبار النشر من الويب");
        // the NEW payload is the current planning — the comparison runs on
        // whitespace-collapsed text because PostgreSQL jsonb normalizes the
        // stored JSON on the way out (key order and separators are the
        // column's own, never the write's), and on the HTML-unescaped text
        // because the board renders the payload through th:text: the
        // documented default escapes the JSON's quotes into &quot; (the
        // safe face of operator-supplied content — the textarea
        // placeholder rides a single-quoted attribute and keeps its raw
        // form, but the published payload's home is the escaped pre
        // block). The assertion pins the SEMANTIC payload either way.
        assertThat(after.replaceAll("\\s+", "")
                .replace("&quot;", "\"")).contains("\"visible\":false");

        // the refusal: an unknown component code answers the banner, nothing stored
        String csrfAfter = csrfOf(after);
        mockMvc.perform(post("/console/planning/publish")
                        .cookie(new Cookie("SESSION", session))
                        .param("payload", "{\"sections\":[{\"code\":\"NOT_A_COMPONENT\"}]}")
                        .param("_csrf", csrfAfter))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console/planning"));

        String refusedBoard = mockMvc.perform(get("/console/planning")
                        .cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusedBoard).contains("Unknown section code");
        // the trail still carries exactly the two revisions (1 seed + 1 publish)
        assertThat(refusedBoard).doesNotContain("نُشرت المراجعة رقم 3");
    }

    /**
     * The queue boards' own contract (the #570 wave-2 boards): the page
     * renders its four actionable queues for the admin (the honest empty
     * states included — no fabricated rows), and a verdict POST through
     * the real chain (CSRF honored) reaches the REAL service: an unknown
     * review's id answers the service's own 404, mapped to the flash
     * banner on the redirected board — the full wiring (login → CSRF →
     * service → exception mapping → flash → redirect) pinned without
     * seeding a single queue row.
     */
    @Test
    void theQueueBoardsRenderAndAnUnknownVerdictAnswersTheServiceBanner() throws Exception {
        // anonymous → the login redirect (the form-login chain's entry point)
        mockMvc.perform(get("/console/queues"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // the authenticated non-admin → the role gate
        String userSession = loginForSession(USER_USERNAME);
        mockMvc.perform(get("/console/queues").cookie(new Cookie("SESSION", userSession)))
                .andExpect(status().isForbidden());

        // the admin → the four boards render (their honest empty states)
        String session = loginForSession(ADMIN_USERNAME);
        String page = mockMvc.perform(get("/console/queues").cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("طوابير المراجعة")))
                .andExpect(content().string(containsString("توثيق الجوار")))
                .andExpect(content().string(containsString("توثيق المؤسسات")))
                .andExpect(content().string(containsString("إشراف المراجعات")))
                .andExpect(content().string(containsString("إشراف المجتمع")))
                .andReturn().getResponse().getContentAsString();

        // the verdict POST: an unknown review answers the REAL service's 404
        // through the banner (the CSRF token parsed from the rendered board)
        String csrf = csrfOf(page);
        mockMvc.perform(post("/console/queues/reviews/" + UUID.randomUUID() + "/moderate")
                        .cookie(new Cookie("SESSION", session))
                        .param("action", "APPROVE")
                        .param("_csrf", csrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console/queues"));

        String after = mockMvc.perform(get("/console/queues").cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(after).contains("لا مراجعة بهذا المعرّف");
    }

    /**
     * The real form login, the remember-me suite's own shape: GET the
     * login page, parse its {@code _csrf} input, POST the credentials
     * riding the page's session cookie, and return the post-login
     * SESSION cookie (session-fixation protection rotates the id at
     * login, so the cookie on THIS response is the live one).
     */
    private String loginForSession(String username) throws Exception {
        MvcResult page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn();
        String loginCsrf = csrfOf(page.getResponse().getContentAsString());

        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("_csrf", loginCsrf)
                        .cookie(page.getResponse().getCookies()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        return cookieValue(login.getResponse(), "SESSION");
    }

    private String consolePage(String session) throws Exception {
        return mockMvc.perform(get("/console").cookie(new Cookie("SESSION", session)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String csrfOf(String html) {
        Matcher forward = CSRF_INPUT.matcher(html);
        if (forward.find()) {
            return forward.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(html);
        if (reversed.find()) {
            return reversed.group(1);
        }
        throw new IllegalStateException("no _csrf input found on the page");
    }

    private static String cookieValue(MockHttpServletResponse response, String name) {
        for (Cookie cookie : response.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        throw new IllegalStateException("no " + name + " cookie on the response");
    }
}
