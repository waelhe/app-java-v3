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
 * 401/403 and private-field access"), carried into the Saleor-458 era
 * by the Agent 1 / B3 pre-login reopening (the CodeRabbit security
 * round on PR #696).
 *
 * <p><b>The reopened anonymous contract, end to end:</b> the §13 decision
 * record ("Anonymous GraphQL reads: protected contract retained —
 * anonymous /graphql returns 401. Reopen only for an approved
 * public-discovery journey after tested per-field/object authorization")
 * found its approved journey — the Saleor authentication roots are
 * anonymous-callable BY DEFINITION (tokenCreate is the password grant
 * itself), and the campaign's per-root role-matrix tests are the tested
 * authorization the record demanded. The transport now permits the
 * anonymous POST (the register/password-reset public-POST precedent,
 * SecurityConfig), and the app layer's PreLoginGraphQlGate — a
 * WebGraphQlInterceptor parsing the ACTUAL document — is the
 * per-operation wall: an anonymous caller reaches ONLY the enumerated
 * pre-login family; everything else answers the classified AUTHN-001
 * GraphQL error before dispatch (leg 1: a valid query; leg 3: an
 * unparsable document — deny-by-default; leg 5: the operationName
 * spoof, which the gate ignores because it parses the document, not
 * the name). The pre-login family itself executes cleanly for the
 * anonymous caller (leg 6: tokenVerify answers its own payload). The
 * GET surface and the invalid-bearer challenge keep the standing 401
 * problem+json contract (legs 2 and 4).</p>
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
     * Leg 1 of the reopened anonymous contract: a POST carrying a
     * perfectly valid GraphQL QUERY is answered by the pre-login gate —
     * the anonymous caller's document contains a protected operation
     * ({@code services}), so the gate answers the classified AUTHN-001
     * GraphQL error (the same canonical taxonomy pair the exception
     * resolver emits) BEFORE dispatch: HTTP 200 with the error envelope
     * over the application/json media type (the GraphQL-over-JSON
     * contract — the 4xx status belongs to the
     * application/graphql-response+json media type negotiation), and the
     * query never executes.
     */
    @Test
    void anonymousValidQueryPostIsAnsweredByThePreLoginGate() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ services(first: 1) { edges { node { id } } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Authentication is required for this GraphQL operation"));
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
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.category").value("authz"))
                .andExpect(jsonPath("$.instance").value("/graphql"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"marketplace\""))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    /**
     * Leg 3 of the reopened anonymous contract: a POST whose document
     * CANNOT be parsed is denied by the same gate rule — the gate never
     * forwards what it cannot read (deny-by-default), so the anonymous
     * caller answers the classified AUTHN-001 error, not a smuggled
     * execution failure.
     */
    @Test
    void anonymousUnparsableDocumentIsDeniedByDefault() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ this is not a graphql document\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"));
    }

    /**
     * Leg 5 (the anti-spoofing proof): the gate parses the ACTUAL GraphQL
     * document and ignores the {@code operationName} parameter — a
     * spoofed {@code operationName: "tokenCreate"} riding a protected
     * query document is denied exactly like the bare query of leg 1. The
     * operation NAME is client-controlled metadata; the document is the
     * operation.
     */
    @Test
    void anonymousOperationNameSpoofDoesNotBypassTheGate() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ services(first: 1) { edges { node { id } } } }\","
                                + "\"operationName\":\"tokenCreate\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"));
    }

    /**
     * Leg 6 (the pre-login family executes for the anonymous caller):
     * {@code tokenVerify} is a member of the gate's enumerated pre-login
     * operations — its own proof rides in the payload (the token it
     * verifies). The request passes the gate, reaches the owning fetcher
     * and service, and answers the honest clean payload: a garbage token
     * is {@code isValid: false} with NO GraphQL error — the proof that
     * the anonymous surface is exactly as wide as the Saleor
     * authentication contract and not one operation wider.
     */
    @Test
    void anonymousPreLoginOperationExecutesCleanly() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { tokenVerify(token: \""
                                + "not-a-real-jwt\") { isValid } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.tokenVerify.isValid").value(false));
    }

    /**
     * Leg 7 (round 2, the fragment-spread bypass): an allowed field plus a
     * fragment SPREAD that selects a protected root is refused — the gate
     * refuses any top-level selection it cannot decide without resolving
     * the fragment graph (deny-by-default; the previous shape passed the
     * gate and executed the protected field for the anonymous caller).
     */
    @Test
    void anonymousFragmentSpreadRidingAnAllowedFieldIsRefused() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { tokenVerify(token: \\\"x\\\")"
                                + " { isValid } ...Protected }"
                                + " fragment Protected on Mutation {"
                                + " passwordChange(newPassword: \\\"whatever-123\\\")"
                                + " { errors { code } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"));
    }

    /**
     * Leg 8 (round 2, the inline-fragment bypass): an INLINE fragment
     * selecting a protected root under an allowed operation is refused by
     * the same rule — the shape the gate cannot decide never reaches
     * execution.
     */
    @Test
    void anonymousInlineFragmentSelectingAProtectedRootIsRefused() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { ... on Mutation {"
                                + " passwordChange(newPassword: \\\"whatever-123\\\")"
                                + " { errors { code } } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"));
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
