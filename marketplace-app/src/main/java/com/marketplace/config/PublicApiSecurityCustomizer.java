package com.marketplace.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.Collections;
import java.util.List;

/**
 * Declares, in the served OpenAPI contract, the operations the stateless
 * resource-server chain actually serves without authentication. The behavioral
 * authority is and stays {@code SecurityConfig}'s {@code authorizeHttpRequests}
 * rules (marketplace-platform-infra); this customizer mirrors them into the
 * contract so the spec stops promising 401s the service never answers.
 *
 * <p><b>Why (the measured gap, 2026-10-25):</b> {@link OpenApiConfig#marketplaceOpenAPI()}
 * declares one root-level {@code security: [{"bearerAuth": []}]} requirement, so every
 * one of the 412 served operations is advertised as JWT-required. The service's truth
 * is richer: the resource chain deliberately opens a family of public reads and public
 * POSTs (the L30/L34/L36/L38/L39/S5/W1/W4/W5/A-04/A-18/B-12/B-13/B-16/S1-B1 governance
 * lines in SecurityConfig). Every consumer that trusts the advertised contract is
 * misled — the measured case is TestSprite's AI plan generator (run 2026-10-25, 455
 * generated tests against the live production spec): it designed negative-auth tests
 * expecting 401 on public surfaces, and the honest 404/409/2xx answers then read as
 * failures. The OpenAPI 3 mechanism for "this operation needs no auth" is an
 * operation-level {@code security: []}, which overrides the root-level requirement
 * per the specification's own inheritance rule — and it serializes: the mapper
 * springdoc 3.1.1 serves the contract with (swagger-core-jakarta 2.2.55
 * ObjectMapperFactory, via ObjectMapperProvider) pins {@code JsonInclude.Include.NON_NULL},
 * which keeps empty lists; the served spec's own root {@code "bearerAuth":[]} scopes
 * array is the live proof. Springdoc's own core uses the same empty-list semantics for
 * the annotated equivalent (OpenAPIService: an empty {@code @SecurityRequirements}
 * container sets {@code operation.setSecurity(Collections.emptyList())}) — this
 * customizer drives the identical official representation from the central access
 * rules instead of scattering annotations across the domain modules.
 *
 * <p><b>Matching semantics — declaration order is the contract (first match wins):</b>
 * the ordered rules below mirror SecurityConfig's authorizeHttpRequests declaration
 * order verbatim — the public blanket lines AND the authenticated carve-outs that
 * ride before them — so a carve-out (e.g. GET /api/v1/jobs/me) keeps the root-level
 * requirement while the public family under it (GET /api/v1/jobs/{id}) opens.
 * Matching uses the same {@link AntPathMatcher} the security request matchers are
 * built on, matched against the UNEXPANDED path templates springdoc serves: a
 * {@code {id}} template segment is one opaque segment, exactly what {@code *} and
 * {@code **} match at runtime. An operation that matches no rule keeps the root-level
 * requirement — the {@code anyRequest().authenticated()} default, which is also the
 * honest representation for ADMIN-role surfaces (the spec expresses the auth
 * requirement, not the role gate; {@code hasRole("ADMIN")} enforcement stays
 * SecurityConfig's truth). The operational surfaces outside the springdoc contract
 * (actuator, the docs endpoints themselves, the login/assets chain) are deliberately
 * absent from the list: they never appear in the served paths, and their rules live
 * where they belong.
 *
 * <p><b>Maintenance contract:</b> when an access rule changes in SecurityConfig, the
 * mirror here changes in the same commit; the sentinel tests in
 * {@code PublicApiSecurityCustomizerTest} guard the load-bearing ones (every public
 * family and every carve-out shape).
 */
public final class PublicApiSecurityCustomizer implements OpenApiCustomizer {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final boolean PUBLIC = true;

    private static final boolean AUTHENTICATED = false;

    /**
     * One ordered access rule — the mirror of one SecurityConfig requestMatchers
     * line. A null method is the method-agnostic rule shape
     * ({@code requestMatchers("/pattern")} with no HttpMethod constraint).
     */
    private record AccessRule(HttpMethod method, String pattern, boolean publicAccess) {
    }

    /**
     * The ordered rules of the stateless resource-server chain, in SecurityConfig's
     * declaration order (first match wins), restricted to the surfaces the springdoc
     * contract can serve. Each entry cites the governance line it mirrors.
     */
    private static final List<AccessRule> RULES = List.of(
            // S4/N3: the STOMP handshake plumbing — public BY DESIGN; the authorization
            // boundary is the STOMP message layer (WebSocketSecurityConfig), not the upgrade.
            rule(HttpMethod.GET, "/ws/**", PUBLIC),
            // L39 (realestate systems plan §5 — SEO): the crawler surfaces at their
            // RFC 9309 root paths — anonymous by design (SeoController).
            rule(HttpMethod.GET, "/sitemap.xml", PUBLIC),
            rule(HttpMethod.GET, "/robots.txt", PUBLIC),
            // L38: the completeness score is the PROVIDER'S OWN read under ownership —
            // the carve-out riding BEFORE the blanket listings line (first match wins).
            rule(HttpMethod.GET, "/api/v1/listings/*/completeness", AUTHENTICATED),
            // L34 (realestate systems plan §5): the public catalog browse family.
            rule(HttpMethod.GET, "/api/v1/listings/**", PUBLIC),
            rule(HttpMethod.GET, "/api/v1/reviews/**", PUBLIC),
            rule(HttpMethod.GET, "/api/v1/search/**", PUBLIC),
            // S5 (comprehensive repair plan §10/2.2): the listing's public media read —
            // time-limited presigned URLs only; the WRITE surfaces stay authenticated.
            rule(HttpMethod.GET, "/api/v1/media/listings/*", PUBLIC),
            // W1 (yelp-level plan §4.4 — greptile W1 r8): the published review's photos,
            // S5's exact mirror (the review's own moderation contract decides visibility).
            rule(HttpMethod.GET, "/api/v1/media/reviews/by-review/*", PUBLIC),
            // L30 (realestate systems plan): the administrative hierarchy — public
            // reference data, the anonymous browse pattern.
            rule(HttpMethod.GET, "/api/v1/geo/**", PUBLIC),
            // L36: the agent/office PUBLIC page («نقطة عامة») — precise pattern; the
            // plain profile read and the /providers/me/** owner surfaces stay authenticated.
            rule(HttpMethod.GET, "/api/v1/providers/*/public", PUBLIC),
            // W4 (yelp-level plan §5 — G28): the PUBLIC reviewer page, L36's mirror on
            // the identity path family; the /users/me owner surfaces stay authenticated.
            rule(HttpMethod.GET, "/api/v1/users/*/public", PUBLIC),
            // A-18 (compliance plan C.12 — the §7/2 first-screen moment): the public
            // platform-release read, the bootstrap-settings family called before any
            // authenticated session exists.
            rule(HttpMethod.GET, "/api/v1/releases/**", PUBLIC),
            // B-12 (compliance plan C.2): the public jobs board + detail, with the
            // owner surfaces and the applications inbox carved out FIRST.
            rule(HttpMethod.GET, "/api/v1/jobs/me", AUTHENTICATED),
            rule(HttpMethod.GET, "/api/v1/jobs/me/**", AUTHENTICATED),
            rule(HttpMethod.GET, "/api/v1/jobs/*/applications/**", AUTHENTICATED),
            rule(HttpMethod.GET, "/api/v1/jobs/**", PUBLIC),
            // B-13 (compliance plan C.3): the institution registry board + public page,
            // the jobs lines' exact mirror with the /me owner surface carved out first.
            rule(HttpMethod.GET, "/api/v1/institutions/me", AUTHENTICATED),
            rule(HttpMethod.GET, "/api/v1/institutions/me/**", AUTHENTICATED),
            rule(HttpMethod.GET, "/api/v1/institutions/**", PUBLIC),
            // B-16 (compliance plan C.8 — the M2 store wave): the two public store reads —
            // the product Q&A feed and the seller summary.
            rule(HttpMethod.GET, "/api/v1/store/products/*/questions", PUBLIC),
            rule(HttpMethod.GET, "/api/v1/store/sellers/*/summary", PUBLIC),
            // The PSP webhook ingress — authenticated by per-provider SIGNATURE at the
            // application layer, never by a bearer; the public-POST family's first member.
            rule(null, "/api/v1/payments/webhooks/**", PUBLIC),
            // S1/B1 (platform-readiness audit §6 gate B1): the public account-birth POST —
            // the request's own body IS the credential being created.
            rule(HttpMethod.POST, "/api/v1/auth/register", PUBLIC),
            // A-04 (official-compliance plan §6 wave A — A.1/A.2): the four token-redemption
            // surfaces — every one PRECEDES an authenticated session by definition; the
            // out-of-band single-use token is the proof, never a session.
            rule(HttpMethod.POST, "/api/v1/auth/password-reset/request", PUBLIC),
            rule(HttpMethod.POST, "/api/v1/auth/password-reset/complete", PUBLIC),
            rule(HttpMethod.POST, "/api/v1/auth/email-verification/resend", PUBLIC),
            rule(HttpMethod.POST, "/api/v1/auth/email-verification/complete", PUBLIC),
            // L34 (realestate systems plan §5): the public lead submission
            // («بلا مصادقة إلزامية» — the guest fills name and phone).
            rule(HttpMethod.POST, "/api/v1/listings/*/leads", PUBLIC),
            // W5 (yelp-level plan §5 — G24): the public click recording, the
            // leads/webhooks public-POST precedent.
            rule(HttpMethod.POST, "/api/v1/ads/listings/*/clicks", PUBLIC),
            // The admin vocabulary stays closed: the root-level bearerAuth requirement is
            // the honest spec representation (OpenAPI expresses the auth requirement, not
            // the role gate — hasRole("ADMIN") stays SecurityConfig's enforcement truth).
            rule(null, "/api/v1/admin/**", AUTHENTICATED));

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, pathItem) -> {
            mark(path, HttpMethod.GET, pathItem.getGet());
            mark(path, HttpMethod.POST, pathItem.getPost());
            mark(path, HttpMethod.PUT, pathItem.getPut());
            mark(path, HttpMethod.DELETE, pathItem.getDelete());
            mark(path, HttpMethod.PATCH, pathItem.getPatch());
        });
    }

    private void mark(String path, HttpMethod method, Operation operation) {
        if (operation != null && isPublic(method, path)) {
            // The OpenAPI 3 "no auth required" representation: an operation-level
            // empty list replaces the root-level bearerAuth requirement (the same
            // semantics springdoc's own core applies for an empty @SecurityRequirements).
            operation.setSecurity(Collections.emptyList());
        }
    }

    private boolean isPublic(HttpMethod method, String pathTemplate) {
        for (AccessRule rule : RULES) {
            if ((rule.method() == null || rule.method().equals(method))
                    && PATH_MATCHER.match(rule.pattern(), pathTemplate)) {
                return rule.publicAccess();
            }
        }
        return false;
    }

    private static AccessRule rule(HttpMethod method, String pattern, boolean publicAccess) {
        return new AccessRule(method, pattern, publicAccess);
    }
}
