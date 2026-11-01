# HANDOFF → A0 — The pre-login GraphQL security decision (PR #696, Agent 1)

**Status: PROPOSED — awaiting A0's decision. This PR no longer modifies
`marketplace-platform-infra/.../SecurityConfig.java` directly (the agent
assignment's ownership boundary); the change is delivered here, as a
proposal, per the campaign's shared-file protocol.**

## 1) The decision A0 is asked to make

Open the GraphQL transport's anonymous POST surface — EXACTLY as wide as
the Saleor authentication contract and not one operation wider:

```java
// In the api SecurityFilterChain's authorizeHttpRequests, alongside the
// register/password-reset public-POST precedent:
.requestMatchers(HttpMethod.POST, "/graphql").permitAll()
```

- **POST only.** The GET surface keeps the standing 401 problem+json
  contract (leg 2 of the existing security suite).
- **The gate is the wall, not the matcher.** `PreLoginGraphQlGate` (a
  `WebGraphQlInterceptor`, spring-graphql's officially supported
  interception point — DELIVERED AND TESTED IN THIS PR, agent scope)
  parses the ACTUAL GraphQL document and denies-by-default: an anonymous
  caller reaches execution ONLY when every top-level selection is a
  plain `Field` whose name is in the enumerated pre-login family below.
  Fragment spreads and inline fragments at the top level are refused
  outright (round 2's bypass closure); unparsable documents are refused;
  the `operationName` parameter is ignored (it is client-controlled
  metadata, not the operation).

## 2) The precise allowlist (the gate's enumerated family)

`tokenCreate`, `tokenRefresh`, `tokenVerify`, `requestPasswordReset`,
`confirmAccount`, `setPassword`, `confirmEmailChange`,
`accountRegister`, `accountDelete`.

Every one carries its own proof in the payload (credentials, a rotation
token, a verifiable token, a single-use out-of-band redemption right) or
CREATES the account. Everything else in the schema stays
deny-by-default for the anonymous caller; every AUTHENTICATED request
passes through untouched to the per-fetcher permission catalog
(`SaleorPermissionCatalog`) and the owning services' role/IDOR walls.

## 3) Why the agent did not land it directly

The assignment packet forbids this agent from modifying
`marketplace-platform-infra/.../SecurityConfig.java` (shared file). The
round-1 closure landed the change out of necessity (the anonymous
surface was otherwise unreachable) — the integrator's review correctly
returned it to the ownership boundary. This PR now carries the gate and
its tests ONLY; the surface decision belongs to A0.

## 4) The acceptance criteria A0's landing must satisfy

An HTTP test on the REAL application context (the full
`@SpringBootTest` + real security chain — MockMvc with
`springSecurity()`, the `GraphQlSecurityIntegrationTest` pattern) must
prove:

1. **Only the pre-login family passes for the anonymous caller** — a
   `tokenVerify` POST answers its own honest payload
   (`isValid: false` for a garbage token, NO GraphQL error).
2. **Protected queries are rejected** — a valid anonymous QUERY
   selecting a protected root (`services`) answers the classified
   AUTHN-001 GraphQL error before dispatch (HTTP 200 + the error
   envelope over `application/json`).
3. **Unparsable documents are rejected** (deny-by-default).
4. **The `operationName` spoof is rejected** — the gate parses the
   document, not the name.
5. **Fragment shapes are rejected** — a fragment SPREAD (and an inline
   fragment) riding an allowed field is refused.
6. **The GET surface and invalid-bearer challenge keep the standing 401
   problem+json contract.**
7. **The registered user's permission matrix continues unchanged** (the
   per-root role-matrix legs in `AgentOneMutationDgsTest` and the
   integration suites — already green in this PR).

## 5) The test legs, verbatim (land WITH the SecurityConfig change)

The exact reopened-contract legs this PR carried in round 1 (before the
ownership return) are preserved below — A0 lands them in
`GraphQlSecurityIntegrationTest` together with the matcher change:

```java
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
```

## 6) The evidence this PR delivers with the proposal

- `PreLoginGraphQlGate` — the gate itself, wired as a
  `WebGraphQlInterceptor` bean (agent scope, in this PR).
- `PreLoginGraphQlGateTest` — 9/9 legs: the gate's FULL decision table
  (allowed family passes; every protected root refuses; unknown
  operations refuse; empty selection sets refuse; unparsable documents
  refuse; fragment spreads refuse; inline fragments refuse;
  authenticated callers pass through untouched).
- The authenticated role-matrix legs across the packet's suites remain
  green — the surface change never widens what an authenticated caller
  can do.

## 7) Sequencing constraint (why this cannot land in this PR)

The gate lives in the app module (agent scope, this PR); the matcher
lives in the platform-infra module (A0 scope). Landing the matcher on
the integration branch BEFORE the gate merges there would open the
anonymous surface to the WHOLE schema on the integration branch — a
security hole. The correct order is: merge this PR (the gate reaches
integration) → land the matcher + the legs above in A0's own commit →
the reopened-contract HTTP suite proves the anonymous path end to end
on the real context.
