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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * <p><b>The caller key is the RIGHTMOST raw X-Forwarded-For entry</b> (falling
 * back to the container's socket peer address when the header is absent) —
 * the single-trusted-proxy rule of the MDN/OWASP guidance: the rightmost
 * entry is the one the trusted edge appended, so a client-supplied spoofed
 * entry (which the edge appends AFTER) can never displace it. Reading the
 * key from the WRAPPED request was the wave-4 #806 review's Major finding:
 * under FRAMEWORK the ForwardedHeaderExtractingRequest wrapper SUPPRESSES
 * the raw header ({@code getHeader} answers null — spring-web 7.0.10
 * bytecode) and answers {@code getRemoteAddr()} with the LEFTMOST XFF
 * entry ({@code ForwardedHeaderUtils#getLeftMostValue} — spring-web
 * 7.0.10 bytecode), which the client controls whenever the trusted edge
 * APPENDS its hop — the attacker rotated a spoofed header per request and
 * out-ran its budget entirely. The guard therefore reads the raw header
 * from the CONTAINER's root request (see {@link #remoteAddressOf}).</p>
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

    /** The shared configuration name every per-caller ADDRESS instance carries (yml). */
    static final String PER_CALLER_CONFIG = "perCallerGrant";

    /**
     * The shared configuration name every ACCOUNT-keyed instance carries
     * (yml) — SEPARATE from the address budget (the wave-4 #806 review's
     * second-cycle Major · Security & Privacy finding).
     */
    static final String PER_ACCOUNT_CONFIG = "perAccountGrant";

    /** The programmatic default when the shared registry/yml is absent (slices). */
    private static final RateLimiterConfig DEFAULT_PER_CALLER_CONFIG =
            RateLimiterConfig.custom()
                    .limitForPeriod(10)
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .timeoutDuration(Duration.ZERO)
                    .build();

    /**
     * The programmatic account-budget default: STRICTLY EXCEEDS the address
     * budget so a single address can never alone exhaust an account's
     * budget — the login-lockout the review demonstrated (ten
     * {@code tokenCreate} calls with the victim's email) requires at least
     * three addresses now, while the distributed stuffing cap against one
     * account stays bounded.
     */
    private static final RateLimiterConfig DEFAULT_PER_ACCOUNT_CONFIG =
            RateLimiterConfig.custom()
                    .limitForPeriod(30)
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
                .addRateLimiterConfig(PER_ACCOUNT_CONFIG, DEFAULT_PER_ACCOUNT_CONFIG)
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
        acquirePermitForKey(environment, surface, remoteAddressKey(environment),
                PER_CALLER_CONFIG, DEFAULT_PER_CALLER_CONFIG);
    }

    /**
     * Consumes one permit of the ACCOUNT's own budget for the surface —
     * the wave-4 review (Minor · Security & Privacy): the address key is
     * alone, so an attacker distributing attempts across many addresses
     * faces no limit per victim account (credential stuffing). The key
     * is the SHA-256 of the NORMALIZED email argument (lower-cased,
     * trimmed — the same normalization the identity owner applies to
     * account lookups), never the raw address: the hash is one-way and
     * fixed-width, so the bounded registry never carries an attacker-
     * controlled key longer than the digest, and the store reveals
     * nothing about the attempted accounts.
     *
     * <p><b>The account budget rides its OWN {@code perAccountGrant}
     * configuration, NEVER the address budget's {@code perCallerGrant}
     * (the wave-4 #806 review's second-cycle Major · Security & Privacy
     * finding).</b> Sharing the address budget's 10/min let ONE address
     * burn the victim's ACCOUNT budget — ten {@code tokenCreate} calls
     * with the victim's email — after which the victim's own login from
     * their OWN address answered {@code RequestNotPermitted} from the
     * account budget: a login lockout an unauthenticated attacker
     * administers remotely. {@code perAccountGrant} strictly EXCEEDS the
     * address budget (3× by default), so a single address can only ever
     * consume a third of an account's budget and the legitimate owner
     * keeps headroom, while distributed credential stuffing against one
     * account remains capped at the account budget's own limit. Counting
     * failed attempts only was evaluated and rejected at this layer: the
     * guard is a pre-flight boundary budget and attempt OUTCOMES are the
     * identity use case's internals — coupling the boundary to use-case
     * outcomes would cross the module boundary the Modulith architecture
     * pins.</p>
     *
     * @param environment the data fetcher's environment (arguments carry
     *                    the email)
     * @param surface     the surface name — the account instances ride
     *                    {@code <surface>-account:<sha256>} keys with
     *                    the SEPARATE {@code perAccountGrant} config
     * @throws RequestNotPermitted the account's own budget is exhausted
     */
    public void acquireAccountPermit(DataFetchingEnvironment environment, String surface) {
        String accountKey = accountKeyOf(environment);
        if (accountKey == null) {
            // No email argument on this request (a malformed input the
            // root's own validation rejects right after) — nothing to
            // budget beyond the address permit already taken.
            return;
        }
        acquirePermitForKey(environment, surface + "-account", accountKey,
                PER_ACCOUNT_CONFIG, DEFAULT_PER_ACCOUNT_CONFIG);
    }

    /**
     * Consumes one permit of the keyed budget. The CONFIG is resolved per
     * FAMILY — the address budget rides {@code perCallerGrant}, the account
     * budget rides the SEPARATE {@code perAccountGrant} (the wave-4 #806
     * review's second-cycle Major finding: a shared config let one address
     * exhaust the victim's account budget and lock the victim's login out)
     * — each from the shared auto-configured registry when it declares it
     * (the full application context), else the guard's own programmatic
     * default of that family.
     */
    private void acquirePermitForKey(DataFetchingEnvironment environment, String surface, String key,
                                     String configName, RateLimiterConfig fallback) {
        // The keyed budgets ride the yml's configs when the shared registry
        // declares them (the full application context); the guard's own
        // bounded registry owns the instances either way.
        RateLimiterConfig config = sharedRegistry.getIfAvailable() != null
                ? sharedRegistry.getObject().getConfiguration(configName)
                        .orElse(fallback)
                : fallback;
        RateLimiter limiter = perCallerRegistry.rateLimiter(
                surface + "-caller:" + key, config);
        if (!limiter.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(limiter);
        }
    }

    /**
     * The caller key for the surface's address budget: the socket peer
     * address, or the unresolvable-context bucket (a non-HTTP execution,
     * e.g. the test executor — the fail-safe shared direction).
     */
    private static String remoteAddressKey(DataFetchingEnvironment environment) {
        String remoteAddress = remoteAddressOf(environment);
        return remoteAddress == null ? "unknown" : remoteAddress;
    }

    /**
     * The account budget key: the SHA-256 hex of the NORMALIZED email
     * argument (lower-cased, trimmed) — or null when this request carries
     * no email argument. Handles both the flat argument shape (the
     * {@code tokenCreate(email: ...)} root) and the nested-input shape
     * ({@code input.email} / {@code input.emailAddress}).
     */
    private static String accountKeyOf(DataFetchingEnvironment environment) {
        String email = argumentEmail(environment);
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(email.trim().toLowerCase()
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // Every JDK carries SHA-256 (MessageDigest requirements).
            throw new IllegalStateException("SHA-256 digest unavailable", impossible);
        }
    }

    private static String argumentEmail(DataFetchingEnvironment environment) {
        Object flat = environment.getArgument("email");
        if (flat instanceof String text && !text.isBlank()) {
            return text;
        }
        for (String container : new String[] {"input", "emailInput"}) {
            Object nested = environment.getArgument(container);
            if (nested instanceof Map<?, ?> map) {
                for (String field : new String[] {"email", "emailAddress"}) {
                    Object value = map.get(field);
                    if (value instanceof String text && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The caller key through the DGS request context.
     *
     * <p><b>The proxy contract (the wave-4 #806 review's Major finding):</b>
     * production runs {@code server.forward-headers-strategy=FRAMEWORK}
     * (the ForwardedHeaderFilter — the adopted plan's host/port/scheme
     * requirement, pinned by the ForwardHeadersProdConfigTest gate), under
     * which the request is wrapped in
     * {@code ForwardedHeaderExtractingRequest}. That wrapper SUPPRESSES the
     * raw forwarded headers ({@code getHeader("X-Forwarded-For")} answers
     * null, {@code getHeaders} an empty enumeration — the
     * {@code ForwardedHeaderRemovingRequest} parent, verified against
     * spring-web 7.0.10) and answers {@code getRemoteAddr()} with the
     * FRAMEWORK-RESOLVED address — the LEFTMOST XFF entry
     * ({@code ForwardedHeaderUtils#getLeftMostValue}, verified), which the
     * client controls whenever the trusted edge APPENDS its own hop.
     * Keying the budget on either of those let one caller rotate a spoofed
     * header per request and out-run its per-caller budget entirely. The
     * fix reads the raw edge-delivered header instead: the wrapper chain
     * is walked to the CONTAINER's root request through the standard
     * {@code ServletRequestWrapper.getRequest()} composition (jakarta
     * Servlet spec — no server-specific API), where the edge-delivered
     * X-Forwarded-For is intact, and the RIGHTMOST entry becomes the key
     * (the MDN X-Forwarded-For and OWASP rate-limiting guidance for a
     * SINGLE trusted proxy — a spoofed client value can only ever sit to
     * its LEFT). With no XFF header present (direct access, tests) the
     * root's socket peer address is the key. Host/port/scheme resolution
     * stays the FRAMEWORK wrapper's job; only the SECURITY key's
     * derivation reads the raw header.</p>
     */
    private static String remoteAddressOf(DataFetchingEnvironment environment) {
        DgsRequestData requestData = DgsContext.getRequestData(environment);
        if (requestData instanceof DgsWebMvcRequestData webMvc
                && webMvc.getWebRequest() instanceof ServletWebRequest servletWebRequest) {
            jakarta.servlet.http.HttpServletRequest request =
                    rootRequestOf(servletWebRequest.getRequest());
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int lastComma = forwarded.lastIndexOf(',');
                String rightmost = (lastComma < 0
                        ? forwarded
                        : forwarded.substring(lastComma + 1)).trim();
                if (!rightmost.isEmpty()) {
                    return rightmost;
                }
            }
            return request.getRemoteAddr();
        }
        return null;
    }

    /**
     * The CONTAINER's root request. The FRAMEWORK strategy's
     * {@code ForwardedHeaderExtractingRequest} hides the raw forwarded
     * headers from the wrapped view and resolves
     * {@code getRemoteAddr()} to the client-controllable LEFTMOST XFF
     * entry (both verified against spring-web 7.0.10 bytecode; Boot 4.1.2
     * registers the filter at {@code Integer.MIN_VALUE}, so no filter can
     * precede the wrapper to capture the raw header) — the SECURITY key
     * therefore walks the wrapper chain to the root request through the
     * standard {@code ServletRequestWrapper#getRequest()} composition and
     * reads the edge-delivered header there. The loop terminates at the
     * first non-wrapper by construction (the container's own request
     * object), whatever the wrapper stack above it looks like.
     */
    private static jakarta.servlet.http.HttpServletRequest rootRequestOf(
            jakarta.servlet.http.HttpServletRequest request) {
        jakarta.servlet.ServletRequest current = request;
        while (current instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return (jakarta.servlet.http.HttpServletRequest) current;
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
