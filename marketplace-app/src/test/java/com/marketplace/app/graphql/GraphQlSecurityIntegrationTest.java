package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * <p><b>The reopened contract (A0's landing of Agent 1's
 * handoff-a0-prelogin-graphql-security.md — the Saleor 458-root
 * campaign):</b> the anonymous POST surface is now EXACTLY as wide as
 * the Saleor authentication contract — the chain permits the anonymous
 * POST (the register/password-reset public-POST precedent family) and
 * {@code PreLoginGraphQlGate} is the wall: the gate parses the ACTUAL
 * GraphQL document and lets an anonymous caller reach execution ONLY
 * for the enumerated pre-login family (tokenCreate, tokenRefresh,
 * tokenVerify, requestPasswordReset, confirmAccount, setPassword,
 * confirmEmailChange, accountRegister, accountDelete — every one
 * carries its own proof in the payload or CREATES the account).
 * Everything else answers the classified AUTHN-001 GraphQL error before
 * dispatch. This is the §13 decision record's documented reopening
 * condition satisfied — "an approved public-discovery journey after
 * tested per-field/object authorization": the campaign's per-root
 * role-matrix legs are that tested authorization, and the legs below pin
 * the reopened anonymous contract inside the suite: the valid-query POST
 * is answered by the gate (not the 401 problem+json), the GET surface
 * and the invalid-bearer challenge keep the standing 401 problem+json
 * contract, and the gate's own decision table (operationName spoof,
 * fragment spread, inline fragment, unparsable document — all refused)
 * is pinned over the REAL security chain exactly as the handoff's
 * acceptance criteria demand.</p>
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

    /**
     * The execution-service spy (the post-merge integration review's
     * required proof leg): the service at the END of the web interceptor
     * chain — a call reaching {@code execute()} is the observable form of
     * "the gate called chain.next"; the gate's short-circuit answer means
     * the request never gets here. A spy (not a mock): every other leg
     * keeps the REAL execution untouched.
     */
    @MockitoSpyBean
    private ExecutionGraphQlService executionGraphQlService;

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
    /**
     * Leg 1 of the reopened contract: a POST carrying a perfectly valid
     * GraphQL query is now answered by the GATE — the pre-login
     * operation wall parses the document, finds {@code services} outside
     * the enumerated family, and answers the classified AUTHN-001
     * GraphQL error envelope (HTTP 200 + application/json, the error
     * before dispatch) — the security chain no longer 401s the POST
     * itself; the wall moved to where the operation is actually known.
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
     * Leg 3 (the handoff's own leg): a POST whose document does not
     * PARSE is denied by default — the gate refuses what it cannot read,
     * answering the classified AUTHN-001 GraphQL error exactly like an
     * unknown operation.
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
     * Leg 3b (the old broken-payload leg's honest continuation): a POST
     * whose JSON BODY itself is malformed never becomes a GraphQL
     * request at all — the transport's own unreadable-message failure
     * answers the caller (the 400 the MVC message-conversion contract
     * prescribes) before the gate, the schema, or any resolver is
     * involved. The body cannot smuggle a different failure mode past
     * the reopened surface.
     */
    @Test
    void anonymousMalformedJsonBodyIsTheTransport400() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The handoff's spoof leg: the {@code operationName} parameter is
     * client-controlled metadata — the gate parses the DOCUMENT, and a
     * protected root under a spoofed allowed name is refused.
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
     * The handoff's execution leg: an operation INSIDE the pre-login
     * family executes cleanly for the anonymous caller —
     * {@code tokenVerify} answers its own honest payload ({@code
     * isValid: false} for a garbage token, NO GraphQL error).
     */
    @Test
    void anonymousPreLoginOperationExecutesCleanly() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { tokenVerify(token: \\\"not-a-real-jwt\\\") { isValid } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.tokenVerify.isValid").value(false));
    }

    /**
     * The handoff's fragment-spread leg: a fragment SPREAD riding an
     * allowed field is refused — a spread can select any protected root,
     * so the pre-login family cannot be decided without resolving the
     * fragment graph; the gate never resolves what it cannot decide.
     */
    @Test
    void anonymousFragmentSpreadRidingAnAllowedFieldIsRefused() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { tokenVerify(token: \\\"x\\\") { isValid } ...Protected } fragment Protected on Mutation { passwordChange(newPassword: \\\"whatever-123\\\") { errors { code } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"));
    }

    /**
     * The handoff's inline-fragment leg: an INLINE fragment selecting a
     * protected root is refused — the same shape rule, the same
     * deny-by-default verdict.
     */
    @Test
    void anonymousInlineFragmentSelectingAProtectedRootIsRefused() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { ... on Mutation { passwordChange(newPassword: \\\"whatever-123\\\") { errors { code } } } }\"}"))
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

    // ------------------------------------------------------------------
    // The execution proof pair (the post-merge integration review's
    // required leg): the gate does not merely answer an error envelope —
    // it stops the interceptor chain itself (chain.next never runs), so
    // GraphQL EXECUTION never starts for the protected query while the
    // pre-login family reaches it. The spy wraps the execution service
    // sitting at the END of the interceptor chain: one intercepted call
    // to execute() is the observable form of "chain.next happened" over
    // the real HTTP surface.
    // ------------------------------------------------------------------

    /**
     * The blocked leg: the anonymous protected query is answered by the
     * gate's classified AUTHN-001 envelope and the request NEVER reaches
     * {@link ExecutionGraphQlService#execute} — no execution, no data
     * fetcher, no resolver: the wall is before dispatch, proven on the
     * real HTTP surface (not by the response shape alone).
     */
    @Test
    void theGateBlocksTheProtectedQueryBeforeGraphQlExecution() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ services(first: 1) { edges { node { id } } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].extensions.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.errors[0].extensions.category").value("authz"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(executionGraphQlService, never()).execute(any());
    }

    /**
     * The allowed leg: the anonymous PRE-LOGIN operation is not merely
     * error-free — it REACHES GraphQL execution exactly once ({@code
     * tokenVerify} really resolves its own payload over the real HTTP
     * surface), the positive half of the pair the blocked leg needs to
     * mean anything: the gate is a discriminating wall, not a blanket
     * rejection.
     */
    @Test
    void thePreLoginOperationReachesGraphQlExecution() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { tokenVerify(token: \\\"not-a-real-jwt\\\") { isValid } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.tokenVerify.isValid").value(false));

        verify(executionGraphQlService, times(1)).execute(any());
    }
}
