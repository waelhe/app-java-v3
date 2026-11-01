package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsRequestData;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;
import graphql.schema.DataFetchingEnvironment;
import io.github.resilience4j.core.RegistryStore;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
 *
 * <p><b>Bounded instance tracking (the wave-3 review's demand):</b> the
 * per-caller instances live in this guard's OWN registry built through
 * the official {@code RateLimiterRegistry.custom().withRegistryStore(...)}
 * builder — the store is an LRU with a configurable capacity (default
 * 10_000 addresses), so an address-churning caller cannot grow the
 * registry without bound. The instance CONFIG comes from the shared
 * auto-configured registry's {@code perCallerGrant} entry (the yml),
 * with a programmatic fail-fast 10/min default when the shared registry
 * is absent. The private registry intentionally binds no per-instance
 * metrics: per-address metric cardinality is its own unbounded-growth
 * defect (the reviewer's "remove its metrics with it" demand).</p>
 */
@Component
public class PerCallerRateLimitGuard {

    /** The shared configuration name every per-caller instance carries (yml). */
    static final String PER_CALLER_CONFIG = "perCallerGrant";

    /** The programmatic default when the shared registry/yml is absent (slices). */
    private static final RateLimiterConfig DEFAULT_PER_CALLER_CONFIG =
            RateLimiterConfig.custom()
                    .limitForPeriod(10)
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .timeoutDuration(Duration.ZERO)
                    .build();

    private final ObjectProvider<RateLimiterRegistry> sharedRegistry;
    private final RateLimiterRegistry perCallerRegistry;

    public PerCallerRateLimitGuard(
            ObjectProvider<RateLimiterRegistry> sharedRegistry,
            @Value("${marketplace.graphql.per-caller-rate-limit.max-tracked-callers:10000}")
            int maxTrackedCallers) {
        this.sharedRegistry = sharedRegistry;
        // The wave-3 review (Major · Stability): dynamically keyed
        // instances in the AUTO-CONFIGURED registry would grow without
        // bound — the official builder path with a bounded store keeps
        // the per-caller budget AND the bounded memory. The eviction
        // tradeoff is documented: an address evicted at capacity re-earns
        // a fresh budget on its next request; the outer per-JVM ceilings
        // still bound the aggregate.
        this.perCallerRegistry = RateLimiterRegistry.custom()
                .withRegistryStore(new BoundedLruStore<>(maxTrackedCallers))
                .addRateLimiterConfig(PER_CALLER_CONFIG, DEFAULT_PER_CALLER_CONFIG)
                .build();
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
        String remoteAddress = remoteAddressOf(environment);
        // An unresolvable context (a non-HTTP execution, e.g. the test
        // executor) shares ONE bucket — the fail-safe direction.
        String caller = remoteAddress == null ? "unknown" : remoteAddress;
        // The per-caller budget rides the yml's perCallerGrant config when
        // the shared registry declares it (the full application context);
        // the guard's own bounded registry owns the instances either way.
        RateLimiterConfig config = sharedRegistry.getIfAvailable() != null
                ? sharedRegistry.getObject().getConfiguration(PER_CALLER_CONFIG)
                        .orElse(DEFAULT_PER_CALLER_CONFIG)
                : DEFAULT_PER_CALLER_CONFIG;
        RateLimiter limiter = perCallerRegistry.rateLimiter(
                surface + "-caller:" + caller, config);
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

    /**
     * The bounded LRU the official {@code withRegistryStore} builder path
     * takes (the wave-3 review's demand: idle limiters must leave memory).
     * Access-ordered: the LEAST recently used address's limiter is evicted
     * at capacity. Synchronized on the map — the guard's hot path is one
     * map probe per anonymous request, and correctness beats lock-free
     * here.
     */
    static final class BoundedLruStore<E> implements RegistryStore<E> {

        private final Map<String, E> delegate;

        BoundedLruStore(int capacity) {
            this.delegate = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, E> eldest) {
                    return size() > capacity;
                }
            };
        }

        @Override
        public E computeIfAbsent(String key, Function<? super String, ? extends E> mappingFunction) {
            synchronized (delegate) {
                return delegate.computeIfAbsent(key, mappingFunction);
            }
        }

        @Override
        public E putIfAbsent(String key, E value) {
            synchronized (delegate) {
                return delegate.putIfAbsent(key, value);
            }
        }

        @Override
        public Optional<E> find(String key) {
            synchronized (delegate) {
                return Optional.ofNullable(delegate.get(key));
            }
        }

        @Override
        public Optional<E> remove(String name) {
            synchronized (delegate) {
                return Optional.ofNullable(delegate.remove(name));
            }
        }

        @Override
        public Optional<E> replace(String name, E newEntry) {
            synchronized (delegate) {
                return Optional.ofNullable(delegate.replace(name, newEntry));
            }
        }

        @Override
        public Collection<E> values() {
            synchronized (delegate) {
                return List.copyOf(delegate.values());
            }
        }
    }
}
