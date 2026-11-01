package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsRequestData;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;
import graphql.schema.DataFetchingEnvironment;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * The shard review (Major · Stability & Availability, 2026-11-01 wave 2):
 * the per-CALLER budget for the anonymous entry points — the grant path
 * ({@code tokenCreate}/{@code tokenRefresh}), registration
 * ({@code accountRegister}) and the anonymous email senders
 * ({@code requestPasswordReset}/{@code sendConfirmationEmail}).
 *
 * <p><b>Why this exists:</b> the named {@code @RateLimiter} instances are
 * PER JVM INSTANCE (the resilience4j annotation model — the yml's own
 * recorded contract), so the shared {@code graphqlTokenMutation} budget
 * meant the ELEVENTH login attempt platform-wide answered 429: one
 * anonymous client could starve every legitimate caller, and a flood of
 * password-reset requests could take the whole account-mutation family
 * down with it. The root instances stay as the outer platform ceiling
 * (raised to fit expected traffic); THIS guard gives every caller address
 * its OWN fail-fast budget keyed dynamically through the official
 * {@link RateLimiterRegistry#rateLimiter(String, String)} API, so one
 * address can only exhaust itself.</p>
 *
 * <p><b>The caller key is the socket peer address</b> ({@code getRemoteAddr()}
 * through the DGS request context — the official
 * {@code DgsContext.getRequestData(...).getWebRequest()} API on the WebMVC
 * stack). The address is unspoofable; behind a reverse proxy it is the
 * proxy's address, so the per-caller budget degrades to the SAME shared
 * behavior the named instances already had — never worse. The documented
 * upgrade path for a proxied deployment is Spring Boot's own
 * {@code server.forward-headers-strategy=native} +
 * {@code server.tomcat.remoteip.internal-proxies} (the official mechanism):
 * once configured, {@code getRemoteAddr()} answers the resolved client
 * address and this guard becomes truly per-client with no code change.</p>
 *
 * <p>The thrown {@link RequestNotPermitted} flows through the surface's
 * existing classification (GraphQlExceptionResolver maps it to the RL-001
 * taxonomy — the identical fixed sentence the annotated limiters return).</p>
 *
 * <p><b>The registry is an {@link ObjectProvider}:</b> the containerless
 * DGS package-slice tests (the AgentOneMutationDgsTest precedent) scan
 * this package without resilience4j's auto-configuration — the same
 * slices where the {@code @RateLimiter} annotations are equally inert.
 * A slice without the registry makes this guard a no-op, mirroring the
 * annotations' own slice semantics; the full application context always
 * carries the registry (the @RateLimiter aspects' own dependency).</p>
 */
@Component
public class PerCallerRateLimitGuard {

    /** The shared configuration name every per-caller instance carries (yml). */
    static final String PER_CALLER_CONFIG = "perCallerGrant";

    private final ObjectProvider<RateLimiterRegistry> registry;

    public PerCallerRateLimitGuard(ObjectProvider<RateLimiterRegistry> registry) {
        this.registry = registry;
    }

    /**
     * Consumes one permit of the caller's own budget for the surface.
     *
     * @param environment the data fetcher's environment (the DGS request
     *                    context carries the servlet request)
     * @param surface     the surface name, e.g. {@code graphqlTokenMutation}
     * @throws RequestNotPermitted the caller's own budget is exhausted —
     *                             the same classified error the annotated
     *                             limiters raise
     */
    public void acquirePermit(DataFetchingEnvironment environment, String surface) {
        RateLimiterRegistry resolved = registry.getIfAvailable();
        if (resolved == null) {
            // The containerless slice: no resilience4j registry, no budget
            // (the @RateLimiter annotations are equally inert there).
            return;
        }
        String remoteAddress = remoteAddressOf(environment);
        // An unresolvable context (a non-HTTP execution, e.g. the test
        // executor) shares ONE bucket — the fail-safe direction.
        String caller = remoteAddress == null ? "unknown" : remoteAddress;
        RateLimiter limiter = resolved.rateLimiter(surface + "-caller:" + caller,
                PER_CALLER_CONFIG);
        if (!limiter.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(limiter);
        }
    }

    /**
     * The caller's socket address through the DGS request context — the
     * servlet request's own remote address (see the class javadoc for the
     * proxy-deployment contract).
     */
    private static String remoteAddressOf(DataFetchingEnvironment environment) {
        DgsRequestData requestData = DgsContext.getRequestData(environment);
        if (requestData instanceof DgsWebMvcRequestData webMvc
                && webMvc.getWebRequest() instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRemoteAddr();
        }
        return null;
    }
}
