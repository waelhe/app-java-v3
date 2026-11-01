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
import java.util.Locale;
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
 * <p><b>The caller key follows an explicit TWO-PATH trust policy</b> (the
 * wave-6 #806 review, Minor · Functional Correctness) — each leg grounded
 * in a DOCUMENTED contract, never in header parsing heuristics:</p>
 *
 * <ul>
 * <li><b>Internal path</b> (the ROOT request's Host ends with the Railway
 * private-DNS suffix {@code .railway.internal}): the key is the container's
 * socket peer address ({@code getRemoteAddr()}). The Railway platform
 * contract (staff-confirmed, docs.railway.com private networking): requests
 * over {@code *.railway.internal} SKIP the public edge and its header
 * sanitization — any service in the same project environment can send its
 * own forwarding headers, so {@code X-Forwarded-For}/{@code X-Real-IP} are
 * read NOWHERE on this path. The socket peer IS the caller's identity
 * inside the project-environment trust boundary (this deployment's one
 * private caller is its own edge BFF, whose relay target
 * {@code EDGE_BACKEND_URL=http://app-java-v3.railway.internal:8080} gives
 * those requests their internal Host).</li>
 * <li><b>Public path</b> (everything reaching the listener through the
 * Railway HTTP edge): the key is {@code X-Real-IP} — the header Railway's
 * edge OVERWRITES on every public request and documents as "the client's
 * remote IP", its own stated key for public rate limiting — accepted ONLY
 * when it parses as an IP literal (see {@link #isIpLiteral}); when absent
 * (direct/local access, tests — no edge, the socket peer IS the client)
 * the key falls back to {@code getRemoteAddr()}.</li>
 * </ul>
 *
 * <p><b>{@code X-Forwarded-For} is read nowhere:</b> Railway's staff-verified
 * contract states the edge REPLACES it (caller copies never arrive) but does
 * NOT guarantee a fixed hop count or topology — "don't depend on the
 * position of a value or a one-hop limit" — so the previous rightmost-entry
 * key (the wave-4 fix) could silently collapse onto a shared proxy address
 * (several clients, one budget), and on the unsanitized internal path a
 * caller-influenced XFF chain would re-open the very rotation bypass the
 * guard exists to close. Reading the key from the WRAPPED request had been
 * the wave-4 #806 review's Major finding: under FRAMEWORK the
 * ForwardedHeaderExtractingRequest wrapper SUPPRESSES the raw headers
 * ({@code getHeader} answers null — spring-web 7.0.10 bytecode) and answers
 * {@code getRemoteAddr()} with the LEFTMOST XFF entry, which the client
 * controls. The guard therefore reads everything from the CONTAINER's root
 * request (see {@link #remoteAddressOf}, {@link #rootRequestOf}).</p>
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
        // The third-pass #806 review (Minor · Stability): a
        // max-tracked-callers of 0 or below (e.g. a mis-parsed
        // environment variable) makes the LRU evict every entry the
        // moment it is inserted — each request would then find a FRESH
        // limiter and rate limiting would be silently disabled. Fail
        // at startup instead: the constructor rejects the
        // misconfiguration with IllegalArgumentException, the same
        // contract Spring's own Assert.isTrue enforces for bean
        // invariants (the framework fails its context on any bean
        // construction error — a misconfigured guard can never run
        // disabled).
        if (maxTrackedCallers < 1) {
            throw new IllegalArgumentException(
                    "marketplace.graphql.per-caller-rate-limit.max-tracked-callers must be >= 1, got "
                            + maxTrackedCallers);
        }
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
     * is the SHA-256 of the NORMALIZED email argument (trimmed, then
     * lower-cased with {@link Locale#ROOT} — the identity owner's own
     * account-lookup normalization, {@code UserAccountAdminService}'s
     * {@code trim().toLowerCase(Locale.ROOT)}; the JVM-default-locale
     * variant would split one account's budget across keys under
     * locale-sensitive alphabets — the third-pass #806 review's Minor
     * finding), never the raw address: the hash is one-way and
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
            byte[] hashed = digest.digest(email.trim().toLowerCase(Locale.ROOT)
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
     * The caller key through the DGS request context — the TWO-PATH trust
     * policy documented on the class javadoc.
     *
     * <p><b>Why the ROOT request:</b> production runs
     * {@code server.forward-headers-strategy=FRAMEWORK} (the
     * ForwardedHeaderFilter — the adopted plan's host/port/scheme
     * requirement, pinned by the ForwardHeadersProdConfigTest gate), under
     * which the request is wrapped in
     * {@code ForwardedHeaderExtractingRequest}. That wrapper SUPPRESSES the
     * raw forwarded headers ({@code getHeader} answers null, {@code
     * getHeaders} an empty enumeration — the
     * {@code ForwardedHeaderRemovingRequest} parent, verified against
     * spring-web 7.0.10) and answers {@code getRemoteAddr()} with the
     * FRAMEWORK-RESOLVED address — the LEFTMOST XFF entry
     * ({@code ForwardedHeaderUtils#getLeftMostValue}, verified), which the
     * client controls whenever the trusted edge APPENDS its own hop. The
     * wrapper chain is therefore walked to the CONTAINER's root request
     * through the standard {@code ServletRequestWrapper.getRequest()}
     * composition (jakarta Servlet spec — no server-specific API), where
     * the edge-delivered headers and the raw socket peer are intact.
     * Host/port/scheme resolution stays the FRAMEWORK wrapper's job; only
     * the SECURITY key's derivation reads the root.</p>
     *
     * <p><b>Internal path:</b> the root's {@code getServerName()} is the
     * raw Host as delivered (the wrapper never touches it) — on the
     * Railway private mesh it is {@code app-java-v3.railway.internal}
     * (the edge BFF's documented relay target). The key is the socket
     * peer; forwarded headers are not consulted at all (the platform does
     * not sanitize headers on this path, so none of them can be trusted).</p>
     *
     * <p><b>Public path:</b> the key is {@code X-Real-IP} — Railway's edge
     * overwrites it on every public request and documents it as the
     * client's remote IP — taken from the LAST non-blank header LINE (the
     * Jakarta Servlet specification's {@code getHeader} answers only the
     * FIRST line of a multi-line header; {@code getHeaders(name)}
     * enumerates ALL lines in delivery order — the same hardening the
     * third-pass #806 review applied to the previous key), and only when
     * it parses as an IP literal. Absent or non-literal (direct/local
     * access, tests) → the socket peer, which on a no-edge path IS the
     * client.</p>
     */
    private static String remoteAddressOf(DataFetchingEnvironment environment) {
        DgsRequestData requestData = DgsContext.getRequestData(environment);
        if (requestData instanceof DgsWebMvcRequestData webMvc
                && webMvc.getWebRequest() instanceof ServletWebRequest servletWebRequest) {
            jakarta.servlet.http.HttpServletRequest request =
                    rootRequestOf(servletWebRequest.getRequest());
            if (isRailwayInternalPath(request)) {
                // Internal mesh: the socket peer is the calling service's
                // identity inside the project-environment trust boundary;
                // forwarded headers are unsanitized here (Railway's
                // documented contract) and are read nowhere.
                return request.getRemoteAddr();
            }
            String realIp = lastHeaderLine(request, "X-Real-IP");
            if (realIp != null && isIpLiteral(realIp)) {
                return realIp;
            }
            return request.getRemoteAddr();
        }
        return null;
    }

    /**
     * Whether the request arrived over the Railway PRIVATE mesh: the ROOT
     * request's {@code getServerName()} — the raw Host header as delivered
     * to the container, port-stripped per the Servlet spec — ends with
     * {@code .railway.internal} (the platform's documented private-DNS
     * suffix; this deployment's internal relay target is
     * {@code app-java-v3.railway.internal}). Compared case-insensitively
     * under {@link Locale#ROOT} (hostnames are case-insensitive per RFC
     * 3986 §3.2.2; the wave-4 review taught never to case-map without an
     * explicit locale). Public edge traffic carries the public domain —
     * never this suffix — so the suffix is the documented, stable path
     * discriminator the reviewer's policy asks for.
     */
    private static boolean isRailwayInternalPath(
            jakarta.servlet.http.HttpServletRequest request) {
        String host = request.getServerName();
        return host != null
                && host.toLowerCase(Locale.ROOT).endsWith(".railway.internal");
    }

    /**
     * The LAST non-blank line of the named header. The Jakarta Servlet
     * specification's {@code getHeader(name)} answers only the FIRST line
     * when the header arrives on multiple lines — a caller-supplied first
     * line would displace the trusted edge's value; {@code getHeaders(name)}
     * enumerates ALL lines in delivery order (the specification's own
     * accessor for multi-valued headers) and the LAST non-blank line
     * carries the most recent write — on the public path the edge's
     * overwrite. Blank lines are skipped; null means no usable line (the
     * socket-peer fallback applies).
     */
    private static String lastHeaderLine(
            jakarta.servlet.http.HttpServletRequest request, String name) {
        java.util.Enumeration<String> lines = request.getHeaders(name);
        String last = null;
        while (lines != null && lines.hasMoreElements()) {
            String line = lines.nextElement();
            if (line != null && !line.isBlank()) {
                last = line;
            }
        }
        return last == null ? null : last.trim();
    }

    /**
     * The include-list matchers behind {@link #isIpLiteral}: all-IPv4 and
     * all-IPv6 CIDRs through the OFFICIAL Spring Security address
     * machinery — the same {@code InetAddressParser} the framework's own
     * {@code IpAddressMatcher} uses for security-sensitive address
     * decisions. Static (immutable) matchers, built once.
     */
    private static final org.springframework.security.util.matcher.InetAddressMatcher
            ANY_IPV4 =
            org.springframework.security.util.matcher.InetAddressMatchers.fromIpAddress(
                    "0.0.0.0/0");
    private static final org.springframework.security.util.matcher.InetAddressMatcher
            ANY_IPV6 =
            org.springframework.security.util.matcher.InetAddressMatchers.fromIpAddress(
                    "::/0");

    /**
     * Whether the value parses as an IP literal (either family) — the
     * gate the public path's {@code X-Real-IP} must pass before it can
     * key a budget.
     *
     * <p><b>Why this machinery and not hand-rolled parsing or bare
     * {@code InetAddress.getByName}:</b> Spring Security's parser guards
     * resolution with its own hostname-rejection check (a hostname-shaped
     * value is rejected BEFORE any resolution can be attempted) and the
     * JDK's {@code InetAddress.getByName} performs no lookup for
     * colon- or digit-shaped literals — so validation is DNS-free and
     * safe on the guard's hot path, while reusing the framework's
     * tested parser instead of a bespoke regex. Anything unparseable
     * (hostname-shaped, out-of-range octets, junk) throws inside the
     * matcher and lands here as {@code false} — a non-literal header
     * value can never key a budget, exactly the reviewer's demand.</p>
     */
    private static boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return ANY_IPV4.matches(value) || ANY_IPV6.matches(value);
        } catch (IllegalArgumentException notALiteral) {
            return false;
        }
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
            // The class's own invariant, independent of the config
            // boundary above: a capacity below 1 turns the store into
            // an always-evict no-op (rate limiting silently disabled).
            if (capacity < 1) {
                throw new IllegalArgumentException(
                        "BoundedLruStore capacity must be >= 1, got " + capacity);
            }
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
