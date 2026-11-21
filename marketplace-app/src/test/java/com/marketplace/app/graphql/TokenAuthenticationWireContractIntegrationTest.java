package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TST-03 + AUTH-01 (the 2026-11-03 adversarial scan, P1): the token
 * surface's authentication failures are pinned ON THE WIRE, over the real
 * HTTP GraphQL surface with the real security chain, the real SAS
 * {@code DaoAuthenticationProvider} over the real {@code auth_users}
 * store, and the real {@code PlatformTokenGrantService} — the adversarial
 * scan's finding was exactly here: bad credentials surfaced the masked
 * INTERNAL-001 transport envelope instead of the Saleor contract's
 * PAYLOAD error.
 *
 * <p><b>The pinned contract</b> (saleor/saleor 3.23.45,
 * {@code create_token.py} / {@code refresh_token.py} at the release
 * commit): a failed password grant answers {@code
 * data.tokenCreate.errors[0] = {field: "email", message: "Please, enter
 * valid credentials", code: INVALID_CREDENTIALS}} and an invalid refresh
 * token answers {@code data.tokenRefresh.errors[0] = {field:
 * "refreshToken", message: "Incorrect refreshToken", code:
 * JWT_INVALID_TOKEN}} — payload errors, never top-level GraphQL
 * errors.</p>
 *
 * <p><b>The anti-enumeration unification</b> (the platform's recorded
 * divergence row "tokenCreate authentication rejection"): the unknown
 * email, the wrong password and the disabled account answer the IDENTICAL
 * payload subtree — the full {@code data.tokenCreate} object is compared
 * structurally between the three legs, so no account-status oracle rides
 * the response. (The disabled-account collapse of Saleor's own INACTIVE
 * answer is deliberate; the divergence row owns it.)</p>
 *
 * <p><b>The schema is the REAL one</b> (the rotation suite's own context
 * key: {@code spring.flyway.enabled=true} + {@code ddl-auto=none}): the
 * grant's authorization row lands in {@code oauth2_authorization} — a
 * Flyway-owned table the create-drop default context never creates, and
 * the seed's {@code auth_users} rows are the platform's own login store.
 * The legs run through MockMvc over the full real security chain exactly
 * like {@code GraphQlSecurityIntegrationTest}.</p>
 *
 * <p><b>The public client is the REAL one too</b>: the grant's
 * authorization row is built against the configured public client —
 * the official {@code OAuth2PublicClientInitializer} skips its seeding
 * when {@code marketplace.security.oauth2.public-client.*} is unset
 * (the empty non-prod default), and the production grant service then
 * answers 'The public client is not registered'. Setting the two
 * properties makes the INITIALIZER itself bootstrap the client through
 * the official {@code RegisteredClientRepository} — the same mechanism
 * production rides, and the same property key the grant service's own
 * {@code publicClientId} binds.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "marketplace.security.oauth2.public-client.client-id=10b588c6-4e85-43ec-9ecf-c588676774d7",
        "marketplace.security.oauth2.public-client.redirect-uris=https://dashboard.example.com/callback",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TokenAuthenticationWireContractIntegrationTest {

    private static final String ACTIVE_EMAIL = "a1-wire-active@example.com";
    private static final String DISABLED_EMAIL = "a1-wire-disabled@example.com";
    private static final String PASSWORD = "a1-wire-correct-password";

    /** The application's own Jackson 3 mapper (the same API the CmsTranslation IT rides). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    /**
     * The same house setup {@code GraphQlSecurityIntegrationTest} rides:
     * MockMvc over the full test context with the REAL security filter
     * chain (the pre-login gate, the Bearer challenge, the CORS wall) —
     * the legs below measure what an anonymous dashboard client actually
     * receives.
     */
    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        // The principals' whole footprint, deterministic per test run: the
        // login rows (recreated through the official UserDetailsManager —
        // the same channel the rotation suite uses), the identity rows,
        // and any authorization rows a prior leg minted.
        for (String email : java.util.List.of(ACTIVE_EMAIL, DISABLED_EMAIL)) {
            jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", email);
            jdbcTemplate.update("DELETE FROM user_role_assignments WHERE user_id IN "
                    + "(SELECT id FROM users WHERE subject = ?)", email);
            jdbcTemplate.update("DELETE FROM users WHERE subject = ?", email);
            if (userDetailsManager.userExists(email)) {
                userDetailsManager.deleteUser(email);
            }
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(ACTIVE_EMAIL)
                .password("{noop}" + PASSWORD)
                .roles("CONSUMER")
                .build());
        // The disabled leg: a REAL disabled auth_users row (enabled=false —
        // the provider's pre-authentication checks answer the disabled
        // account) with the CORRECT password, so the leg measures the
        // account-state failure alone, not a password failure.
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(DISABLED_EMAIL)
                .password("{noop}" + PASSWORD)
                .roles("CONSUMER")
                .disabled(true)
                .build());
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                """, newUserId(), ACTIVE_EMAIL, ACTIVE_EMAIL, "A1 Wire Active");
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                """, newUserId(), DISABLED_EMAIL, DISABLED_EMAIL, "A1 Wire Disabled");
    }

    private static java.util.UUID newUserId() {
        return java.util.UUID.randomUUID();
    }

    /**
     * Leg (أ) — the full payload on success: the granted token family and
     * the caller's own account projection ride the payload with EMPTY
     * errors (the pre-fix baseline shape, unchanged by this batch).
     */
    @Test
    void correctCredentialsAnswerTheFullPayload() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenCreateQuery(ACTIVE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.tokenCreate.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenCreate.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenCreate.user.email").value(ACTIVE_EMAIL))
                .andExpect(jsonPath("$.data.tokenCreate.errors.length()").value(0))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    /**
     * Leg (ب) — the pinned Saleor contract for a wrong password: the
     * PAYLOAD error {@code INVALID_CREDENTIALS} under {@code email}, NOT
     * the masked INTERNAL-001 transport envelope the adversarial scan
     * measured (the pre-fix behavior), and NOT a top-level GraphQL error.
     */
    @Test
    void wrongPasswordAnswersTheSaleorPayloadErrorNotInternal() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenCreateQuery(ACTIVE_EMAIL, "definitely-the-wrong-password")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.tokenCreate.token").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.tokenCreate.user").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.tokenCreate.errors[0].field").value("email"))
                .andExpect(jsonPath("$.data.tokenCreate.errors[0].message")
                        .value("Please, enter valid credentials"))
                .andExpect(jsonPath("$.data.tokenCreate.errors[0].code")
                        .value("INVALID_CREDENTIALS"));
    }

    /**
     * Legs (ج) + (د) — the anti-enumeration unification: the unknown email
     * and the disabled account answer the IDENTICAL {@code
     * data.tokenCreate} subtree the wrong-password leg answers (a full
     * structural comparison of the payload object, not just the error
     * code) — no account-status oracle on the sign-in surface.
     */
    @Test
    void unknownEmailAndDisabledAccountAnswerTheIdenticalUnifiedPayload() throws Exception {
        JsonNode wrongPassword = tokenCreatePayload(ACTIVE_EMAIL, "definitely-the-wrong-password");
        JsonNode unknownEmail = tokenCreatePayload("a1-wire-ghost@example.com", "whatever-password");
        JsonNode disabledAccount = tokenCreatePayload(DISABLED_EMAIL, PASSWORD);

        assertThat(unknownEmail).isEqualTo(wrongPassword);
        assertThat(disabledAccount).isEqualTo(wrongPassword);
        assertThat(wrongPassword.path("errors").size()).isEqualTo(1);
        assertThat(wrongPassword.path("errors").get(0).path("code").asText())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    /**
     * The rotation's own rejection on the wire: an unknown refresh token
     * answers the pinned {@code JWT_INVALID_TOKEN} payload error under
     * {@code refreshToken} — never a raw GraphQL error.
     */
    @Test
    void unknownRefreshTokenAnswersTheSaleorPayloadError() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { tokenRefresh(refreshToken: \\\"not-a-rotation-value\\\") {"
                                + " token refreshToken errors { field message code } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.tokenRefresh.token").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.tokenRefresh.refreshToken").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.tokenRefresh.errors[0].field").value("refreshToken"))
                .andExpect(jsonPath("$.data.tokenRefresh.errors[0].message")
                        .value("Incorrect refreshToken"))
                .andExpect(jsonPath("$.data.tokenRefresh.errors[0].code")
                        .value("JWT_INVALID_TOKEN"));
    }

    private static String tokenCreateQuery(String email, String password) {
        return "{\"query\":\"mutation { tokenCreate(email: \\\"" + email + "\\\", password: \\\""
                + password + "\\\") { token refreshToken user { email } "
                + "errors { field message code } } }\"}";
    }

    /** Executes one tokenCreate leg and returns the {@code data.tokenCreate} subtree. */
    private JsonNode tokenCreatePayload(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenCreateQuery(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("tokenCreate");
    }
}
