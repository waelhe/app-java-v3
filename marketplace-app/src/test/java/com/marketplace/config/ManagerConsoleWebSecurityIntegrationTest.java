package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
 *   problem+json 401).</li>
 *   <li><b>Authenticated non-admin → 403</b> (the role gate).</li>
 *   <li><b>Authenticated admin → the rendered console</b> — the Arabic
 *   tri-partite page with the flip form carrying the CSRF token
 *   (Thymeleaf's own form integration), and the flip POST honoring CSRF
 *   (403 without it, the post/redirect/get 302 with it).</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ManagerConsoleWebSecurityIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; configuration from the IntegrationContainers factory
    static org.testcontainers.containers.GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    void theAnonymousCallerIsSentToTheLoginPage() throws Exception {
        // The form-login chain's documented entry point: a redirect to
        // /login, never the resource chain's problem+json 401.
        mockMvc.perform(get("/console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser // ROLE_USER — authenticated, not the manager
    void theAuthenticatedNonAdminIsDenied() throws Exception {
        mockMvc.perform(get("/console"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theAdminSeesTheRenderedConsole() throws Exception {
        mockMvc.perform(get("/console"))
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
    @WithMockUser(roles = "ADMIN")
    void theFlipHonorsCsrfAndFollowsThePostRedirectGetPattern() throws Exception {
        // without the token: the chain's documented 403, never a write
        mockMvc.perform(post("/console/flags/some.flag/flip"))
                .andExpect(status().isForbidden());

        // with the token: post/redirect/get back to the console home
        mockMvc.perform(post("/console/flags/some.flag/flip").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console"));
    }
}
