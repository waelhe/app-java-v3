package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase-4 security legs of the GraphQL surface (target design
 * §11-Phase-4: "retain the existing stateless Bearer chain … test
 * 401/403 and private-field access. Keep anonymous GraphQL protected
 * until a public-discovery journey explicitly reopens the decision").
 *
 * <p><b>The measured chain fact (SecurityConfig):</b> {@code /graphql}
 * rides the resource-server securityMatcher chain under
 * {@code anyRequest().authenticated()} — the anonymous contract is the
 * shared problem+json 401 AUTHN-001 entry point the REST surface already
 * regression-tests ({@code SecurityProblemDetailIntegrationTest}), and
 * the three anonymous legs below are the exact tripartite contract the
 * live production smoke has measured since Phase 1 (POST a valid query /
 * GET / POST a broken payload — all 401 for the anonymous caller). This
 * class pins that contract INSIDE the suite, so the protected-contract
 * decision (§13 decision record: "Anonymous GraphQL reads: protected
 * contract retained — anonymous /graphql returns 401. Reopen only for an
 * approved public-discovery journey after tested per-field/object
 * authorization") can no longer regress silently between smokes.</p>
 *
 * <p><b>The 403 leg, measured honestly:</b> no 403 can arise from
 * {@code /graphql} in the bounded surface — the route declares
 * {@code authenticated()} with no role requirement (the role-gated
 * surfaces of this chain are the ADMIN routes outside GraphQL, measured
 * in SecurityConfig), and authorization failures INSIDE GraphQL
 * execution are domain errors of the shared error taxonomy
 * ({@code GraphQlErrorIntegrationTest}), never HTTP statuses. The 403
 * problem+json contract itself stays regression-tested in the SAME
 * security chain by {@code SecurityProblemDetailIntegrationTest} against
 * the ADMIN surface. What this class adds for the plan's
 * "private-field access" reading on THIS surface: the bounded
 * {@code Service} contract has no private field at all (every field is
 * public catalog data — measured against the SDL), and the
 * object-level visibility boundary is the owning SPI's active-only read
 * ({@code CatalogSpi.getActiveById}), whose not-found taxonomy
 * {@code GraphQlErrorIntegrationTest} already proves. Authentication
 * first, then the owning module's visibility rules — nothing else
 * exists to test here, and inventing a role gate now would CONTRADICT
 * the corrected plan (authorization belongs in the owning use-case
 * boundary, which the mutation's ownership-delegation test pins).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GraphQlSecurityIntegrationTest {

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

    private MockMvc mockMvc;

    /**
     * Builds the MockMvc over the full test context with the real security
     * filter chain — the same house setup every security problem-detail
     * contract test uses ({@code SecurityProblemDetailIntegrationTest}).
     */
    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /**
     * Leg 1 of the tripartite anonymous contract: a POST carrying a
     * perfectly valid GraphQL query must be answered by the security
     * chain's 401 problem+json (AUTHN-001) with the RFC 6750 §3.1 bare
     * realm challenge — validity of the query is irrelevant before
     * authentication, and the body never reaches the GraphQL handler.
     */
    @Test
    void anonymousValidQueryPostIsRejectedAs401ProblemDetail() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ services(first: 1) { edges { node { id } } } }\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/graphql"))
                .andExpect(jsonPath("$.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.category").value("authz"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"marketplace\""))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    /**
     * Leg 2: a bare GET on {@code /graphql} is the same anonymous
     * contract — the chain answers before any method-level handling, so
     * the GET-vs-POST distinction the live smoke measures is pinned here
     * as well.
     */
    @Test
    void anonymousGetIsRejectedAs401() throws Exception {
        mockMvc.perform(get("/graphql"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.category").value("authz"))
                .andExpect(jsonPath("$.instance").value("/graphql"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"marketplace\""))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    /**
     * Leg 3: a POST with a broken payload is STILL the authentication
     * leg first — the security chain rejects the anonymous caller before
     * any JSON parsing happens, so a malformed body cannot smuggle a
     * different failure mode past the 401 contract.
     */
    @Test
    void anonymousBrokenPostIsStillAuthenticationFirst() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("AUTHN-001"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"marketplace\""));
    }

    /**
     * RFC 6750 §3: a request that supplied a bearer token which failed
     * validation (the real JwtDecoder rejects a garbage token) must be
     * challenged with {@code error="invalid_token"} and the same
     * problem+json 401 contract — the resource-server DSL entry point
     * routes {@code InvalidBearerTokenException} to the shared handler,
     * exactly as the REST legs of the house security-contract test assert.
     */
    @Test
    void invalidBearerTokenIsRejectedAs401WithInvalidTokenChallenge() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt")
                        .content("{\"query\":\"{ services(first: 1) { edges { node { id } } } }\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.category").value("authz"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("error=\"invalid_token\"")))
                .andExpect(header().exists("X-Correlation-ID"));
    }
}
