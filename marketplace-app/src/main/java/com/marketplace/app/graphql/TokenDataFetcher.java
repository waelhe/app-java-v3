package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CreateToken;
import com.marketplace.app.graphql.generated.types.DeactivateAllUserTokens;
import com.marketplace.app.graphql.generated.types.RefreshToken;
import com.marketplace.app.graphql.generated.types.VerifyToken;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * GraphQL adapter for the Saleor Authentication-domain mutations —
 * {@code tokenCreate} (the dashboard's password-grant sign-in over the
 * platform's own SAS primitives), {@code tokenRefresh} (the rotation),
 * {@code tokenVerify} (the resource server's own decoder) and
 * {@code tokensDeactivateAll} (the caller's sign-out-everywhere).
 *
 * <p>Transport-only adapter (the frozen §7 contract): argument validation
 * and the wire mapping; every rule lives in the owning use cases. The
 * token surface's rate budget is two-layered and configured: the named
 * resilience4j instances are the yml-configured per-JVM outer ceilings
 * ({@code graphqlTokenMutation} 60/min for the grant and the rotation,
 * {@code graphqlTokenVerify} 50/min, {@code graphqlTokenDeactivate}
 * 10/min), and the per-caller guard keys a 10/min fail-fast budget per
 * caller address ({@code perCallerGrant}); {@code tokenCreate} adds the
 * ACCOUNT-keyed budget ({@code perAccount = true} — strictly exceeding
 * the address budget, so one address can only consume a third of an
 * account's budget and the legitimate owner keeps login headroom while
 * distributed stuffing stays capped). The aspect order is deliberate:
 * {@code @PerCallerBudget} (order 100) runs BEFORE resilience4j's
 * {@code RateLimiterAspect} (405), so the caller's own budget is
 * consumed before the JVM ceiling is ever consulted.</p>
 *
 * <p><b>The rotation's wire contract:</b> {@code tokenRefresh} returns the
 * SUCCESSOR refresh token ({@code RefreshToken.refreshToken}) — the
 * platform's rotation is one-time-use (the official SAS semantics the
 * grant service implements), so the client's stored token dies with the
 * rotation and the payload must carry its replacement. This is a
 * platform ADAPT row over Saleor's cookie/CSRF-mode refresh contract,
 * recorded in the completion report's divergence table. The
 * {@code tokenVerify.payload} is the claims map serialized through the
 * application's OWN serializer — the Spring Boot 4.1.1 auto-configured
 * Jackson 3 {@code JsonMapper} (the same bean SystemSettingsService and
 * the platform's JSON surfaces ride) — a strict JSON document, never
 * {@code Map.toString()} output.</p>
 */
@DgsComponent
public class TokenDataFetcher {

    private final PlatformTokenGrantService tokenGrantService;
    private final AccountLifecycleSpi accountLifecycleSpi;
    private final com.marketplace.identity.spi.IdentitySpi identitySpi;
    private final AgentOneGraphQlMappers mappers;
    private final UserGraphQlMapper userMapper;
    private final JsonMapper jsonMapper;

    public TokenDataFetcher(PlatformTokenGrantService tokenGrantService,
                            AccountLifecycleSpi accountLifecycleSpi,
                            com.marketplace.identity.spi.IdentitySpi identitySpi,
                            AgentOneGraphQlMappers mappers,
                            UserGraphQlMapper userMapper,
                            JsonMapper jsonMapper) {
        this.tokenGrantService = tokenGrantService;
        this.accountLifecycleSpi = accountLifecycleSpi;
        this.identitySpi = identitySpi;
        this.mappers = mappers;
        this.userMapper = userMapper;
        this.jsonMapper = jsonMapper;
    }

    /** Saleor {@code tokenCreate(audience, email, password)} — the password grant. */
    @DgsMutation(field = DgsConstants.MUTATION.TokenCreate)
    // The wave-4 review (Major · Stability): the per-caller permit now rides
    // @PerCallerBudget — the aspect's ORDER (100) precedes resilience4j's
    // RateLimiterAspect (405), so the caller's own budget is consumed BEFORE
    // the shared graphqlTokenMutation permit: a rejected flood can no longer
    // burn the shared budget for every other caller (the exact starvation
    // the review demonstrated). perAccount adds the credential-stuffing
    // budget keyed by the normalized email hash.
    @PerCallerBudget(surface = "graphqlTokenMutation", perAccount = true)
    @RateLimiter(name = "graphqlTokenMutation")
    public CreateToken tokenCreate(@InputArgument String audience,
                                   @InputArgument String email,
                                   @InputArgument String password,
                                   graphql.schema.DataFetchingEnvironment environment) {
        if (audience != null && !audience.isBlank()) {
            // Saleor's custom-audience prefix is a JWT customization this
            // platform's single-audience contract does not carry — the
            // declared-but-unexecuted argument answers the classified
            // error (the frozen VAL-001 discipline), never a silent no-op.
            throw new IllegalArgumentException(
                    "tokenCreate audience is not implemented on this platform yet");
        }
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "An email and a password are required for the token grant");
        }
        // AUTH-01 (the 2026-11-03 adversarial scan, P1): the grant's
        // AuthenticationException used to fall through to the generic
        // INTERNAL-001 envelope — the Saleor contract for tokenCreate is
        // a PAYLOAD error, not a transport error. The pinned reference
        // (saleor/saleor 3.23.45, create_token.py) answers every failed
        // password grant as errors:[{field: "email", message: "Please,
        // enter valid credentials", code: INVALID_CREDENTIALS}] — and
        // this platform UNIFIES the whole family: an unknown email, a
        // wrong password and a disabled account all answer the IDENTICAL
        // triple (the provider's own BadCredentialsException already
        // hides the unknown-email distinction; the unified catch extends
        // the same discipline to DisabledException and every other
        // account-state failure — no account-status enumeration on the
        // sign-in surface; the deliberate collapse of Saleor's INACTIVE
        // answer is the recorded divergence row "tokenCreate
        // authentication rejection").
        try {
            PlatformTokenGrantService.IssuedTokens issued = tokenGrantService.grant(email, password);
            return CreateToken.newBuilder()
                    .token(issued.accessToken())
                    .refreshToken(issued.refreshToken())
                    .csrfToken(issued.csrfToken())
                    .user(issued.user() == null ? null : userMapper.toGraphQlUser(issued.user()))
                    .errors(mappers.emptyAccountErrors())
                    .build();
        } catch (BadCredentialsException | DisabledException | LockedException
                | AccountExpiredException | CredentialsExpiredException failure) {
            // The 2026-11-04 adversarial delta review: narrowed to the
            // CLIENT-cause family only — an infra outage (DB down →
            // InternalAuthenticationServiceException) was answered here as
            // "Please, enter valid credentials", masking a 500 as a credential
            // failure; the infra family now falls through to the resolver
            // untouched, while all five client causes keep the
            // anti-enumeration unification (the IDENTICAL payload triple).
            return CreateToken.newBuilder()
                    .errors(mappers.invalidCredentialsAccountErrors())
                    .build();
        }
    }

    /**
     * Saleor {@code tokenRefresh(csrfToken, refreshToken)} — the rotation.
     * The payload carries the SUCCESSOR refresh token: the platform's
     * rotation is one-time-use, so the successor is the only live token
     * after this call (the presented one is gone from the store).
     */
    @DgsMutation(field = DgsConstants.MUTATION.TokenRefresh)
    // The same wave-4 ordering fix: the per-caller budget rides the aspect,
    // ahead of the shared permit (the rotation is an anonymous surface —
    // the proof IS the payload).
    @PerCallerBudget(surface = "graphqlTokenMutation")
    @RateLimiter(name = "graphqlTokenMutation")
    public RefreshToken tokenRefresh(@InputArgument String csrfToken,
                                     @InputArgument String refreshToken,
                                     graphql.schema.DataFetchingEnvironment environment) {
        // The CSRF token is Saleor's cookie-mode refresh protection; the
        // platform returns the refresh token in the payload (no cookie
        // mode), so the argument stays declared-but-unexecuted — the
        // classified error, never a silent no-op.
        if (csrfToken != null && !csrfToken.isBlank()) {
            throw new IllegalArgumentException(
                    "tokenRefresh csrfToken is not implemented on this platform yet");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("A refresh token is required");
        }
        // AUTH-01 (same P1): the rotation's rejection is a PAYLOAD error
        // too — the pinned reference (refresh_token.py at 3.23.45) answers
        // an invalid/expired/revoked refresh token as errors:[{field:
        // "refreshToken", message: "Incorrect refreshToken", code:
        // JWT_INVALID_TOKEN}]. The platform's one-time-use rotation store
        // raises BadCredentialsException for exactly that family (unknown
        // value, consumed value, expired family, foreign client) — every
        // leg answers the SAME wire triple here.
        try {
            PlatformTokenGrantService.IssuedTokens issued = tokenGrantService.refresh(refreshToken);
            return RefreshToken.newBuilder()
                    .token(issued.accessToken())
                    .refreshToken(issued.refreshToken())
                    .user(issued.user() == null ? null : userMapper.toGraphQlUser(issued.user()))
                    .errors(mappers.emptyAccountErrors())
                    .build();
        } catch (BadCredentialsException | DisabledException | LockedException
                | AccountExpiredException | CredentialsExpiredException failure) {
            // The 2026-11-04 adversarial delta review: the same narrowing on
            // the rotation — an infra outage (DB down → InternalAuthentication-
            // ServiceException) was answered as "Incorrect refreshToken",
            // masking a 500 as a token failure; the infra family now falls
            // through to the resolver untouched, while every client cause
            // keeps the anti-enumeration unification (the IDENTICAL payload
            // triple).
            return RefreshToken.newBuilder()
                    .errors(mappers.invalidRefreshTokenAccountErrors())
                    .build();
        }
    }

    /**
     * Saleor {@code tokenVerify(token)} — the resource server's own decoder.
     * The {@code payload} is the claims map serialized through the
     * application's own serializer (the auto-configured Jackson 3
     * {@code JsonMapper}) — a strict JSON document the Dashboard can
     * parse directly ({@code Map.toString()} output is NOT JSON and
     * never was).
     */
    @DgsMutation(field = DgsConstants.MUTATION.TokenVerify)
    // The shard review (Major · Stability, 2026-11-01): tokenVerify is a
    // cheap read-shaped verification that PreLoginGraphQlGate admits for
    // anonymous callers — sharing the 10/min grant budget with
    // tokenCreate/tokenRefresh let one caller's verification traffic
    // starve every grant (and vice versa). It now owns its own budget.
    // The wave-4 review (Minor · Stability & Availability): the shared
    // JVM-wide graphqlTokenVerify budget alone still let ONE anonymous
    // client exhaust it for every OTHER caller (the Dashboard's
    // verification fails platform-wide) — the per-caller budget (the
    // aspect, ahead of the shared permit) gives each address its own
    // fail-fast slice, the same shape tokenCreate/tokenRefresh carry.
    @PerCallerBudget(surface = "graphqlTokenVerify")
    @RateLimiter(name = "graphqlTokenVerify")
    public VerifyToken tokenVerify(@InputArgument String token,
                                   graphql.schema.DataFetchingEnvironment environment) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A token is required");
        }
        PlatformTokenGrantService.VerifiedToken verified = tokenGrantService.verify(token);
        VerifyToken.Builder builder = VerifyToken.newBuilder()
                .isValid(verified.valid())
                .payload(verified.claims().isEmpty() ? null : jsonMapper.writeValueAsString(verified.claims()))
                .errors(mappers.emptyAccountErrors());
        if (verified.valid() && verified.subject() != null) {
            identitySpi.findCurrentUserAccount(
                            new org.springframework.security.authentication
                                    .UsernamePasswordAuthenticationToken(verified.subject(), null, java.util.List.of()))
                    .ifPresent(account -> builder.user(userMapper.toGraphQlUser(account)));
        }
        return builder.build();
    }

    /** Saleor {@code tokensDeactivateAll} — the caller's sign-out-everywhere. */
    @DgsMutation(field = DgsConstants.MUTATION.TokensDeactivateAll)
    // The same review: the authenticated sign-out-everywhere leg must not
    // compete with anonymous grant traffic for one shared budget.
    @RateLimiter(name = "graphqlTokenDeactivate")
    public DeactivateAllUserTokens tokensDeactivateAll() {
        Authentication caller = currentAuthentication();
        accountLifecycleSpi.deactivateAllTokens(caller);
        return DeactivateAllUserTokens.newBuilder()
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    private Authentication currentAuthentication() {
        // B24 (IDP-06): the wall extracted to the identity family's ONE
        // shared authority (the anonymous-type check this fetcher landed
        // first, at 0d1d97dc, with the same measured lesson).
        return AuthenticatedCallers.require("token administration");
    }
}
