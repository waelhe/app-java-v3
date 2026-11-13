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
 * ({@code accountRegister}) and the anonymous reset request
 * ({@code requestPasswordReset}; sendConfirmationEmail is an
 * AUTHENTICATED_USER root in the pinned Saleor 3.23.45 contract and
 * rides the authenticated budget).
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
 * <li><b>Internal path</b> (the ROOT request's socket PEER address is a
 * private-mesh address — RFC 1918 or ULA, the project's documented
 * private-network standard; the documented private-DNS Host suffix
 * {@code .railway.internal} stays as the belt): the key is the
 * container's socket peer address ({@code getRemoteAddr()}). The Railway
 * platform contract (staff-confirmed, docs.railway.com private networking):
 * requests over {@code *.railway.internal} SKIP the public edge and its
 * header sanitization — any service in the same project environment can
 * send its own forwarding headers, so {@code X-Forwarded-For}/{@code
 * X-Real-IP} are read NOWHERE on this path. The socket peer IS the
 * caller's identity inside the project-environment trust boundary (this
 * deployment's one private caller is its own edge BFF, whose relay target
 * {@code EDGE_BACKEND_URL=http://app-java-v3.railway.internal:8080} gives
 * those requests their internal Host). The peer is the PRIMARY
 * discriminator (the 2026-11-08 #806 round-9 review, Major): a
 * private-mesh caller that sends a NON-internal Host used to fall into
 * the public branch and trust a self-controlled {@code X-Real-IP} —
 * rotating fresh budgets at will. The peer address cannot be rotated
 * from inside the mesh, so the private-mesh peer settles the path
 * regardless of the Host header's shape; the Railway HTTP edge's own
 * agents connect from the CGNAT space 100.64.0.0/10 — never RFC
 * 1918/ULA — so the public edge never misclassifies as internal.</li>
 * <li><b>Public path</b> (everything reaching the listener through the
 * Railway HTTP edge): the key is {@code X-Real-IP} — the header Railway's
 * edge OVERWRITES on every public request and documents as "the client's
 * remote IP", its own stated key for public rate limiting — accepted ONLY
 * when it parses as an IP literal (see {@link #isIpLiteral}) and then
 * normalized to the caller's routable PREFIX (see {@link #budgetKeyOf}:
 * IPv6 collapses to its /64, IPv4 stands whole); when absent (direct/local
 * access, tests — no edge, the socket peer IS the client) the key falls
 * back to {@code getRemoteAddr()}.</li>
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

    /** The account-key family's OWN bounded store (round-3: never shared). */
    private final RateLimiterRegistry perAccountRegistry;

    public PerCallerRateLimitGuard(
            ObjectProvider<RateLimiterRegistry> sharedRegistry,
            ObjectProvider<io.github.resilience4j.springboot.ratelimiter.autoconfigure.RateLimiterProperties>
                    rateLimiterProperties,
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
        // The 2026-11-04 #806 round (Minor · Security & Privacy): the
        // account budget MUST strictly exceed the address budget — the
        // defaults carry 10/30, but the yml can override either entry
        // independently; a perCallerGrant raised to the account budget
        // (or beyond) lets ONE address alone exhaust the victim account's
        // whole budget and lock the owner out until the window refreshes.
        // Fail at startup (the same contract the max-tracked-callers
        // check enforces): a misconfigured guard can never run with the
        // lockout it exists to prevent. Package slices without the
        // resilience4j auto-configuration validate the programmatic
        // defaults (10 < 30 — the invariant the defaults document).
        RateLimiterRegistry shared = sharedRegistry.getIfAvailable();
        RateLimiterConfig callerConfig = shared == null
                ? DEFAULT_PER_CALLER_CONFIG
                : shared.getConfiguration(PER_CALLER_CONFIG).orElse(DEFAULT_PER_CALLER_CONFIG);
        RateLimiterConfig accountConfig = shared == null
                ? DEFAULT_PER_ACCOUNT_CONFIG
                : shared.getConfiguration(PER_ACCOUNT_CONFIG).orElse(DEFAULT_PER_ACCOUNT_CONFIG);
        // The 2026-11-05 #806 round-3 review (Major · Security & Privacy):
        // the count-only comparison ignored limitRefreshPeriod — with a
        // SHORTER per-caller window one address can burn
        // callerLimit * ceil(accountWindow / callerWindow) permits inside
        // ONE account window, which can exceed the account budget even
        // when the raw counts compare cleanly (10/10s vs 30/60s: 60 > 30
        // — the address alone locks the account out). The invariant is
        // window-aware now: the MOST one address can obtain within one
        // account window must stay within the account budget.
        requireWindowAwareAccountHeadroom(PER_CALLER_CONFIG, callerConfig, accountConfig);
        // The 2026-11-06 #806 round review (Minor · Security & Privacy):
        // resolveConfig prefers the SURFACE-specific override
        // (<family>-<surface>, e.g. perCallerGrant-graphqlCheckoutMutation)
        // over the family-wide entry the invariant above just validated —
        // a per-surface override could quietly break the lockout
        // protection the family check enforces (and, riding base-config:
        // default, inherit the DEFAULT family's 5s queue timeout instead
        // of the fail-fast zero these budgets document). The SAME
        // invariants therefore validate every declared per-surface
        // override at startup: the official RateLimiterProperties bean
        // (the starter's own configuration surface — the registry API has
        // no name enumeration) supplies the declared names; the registry's
        // own merged configs supply each entry's EFFECTIVE values.
        io.github.resilience4j.springboot.ratelimiter.autoconfigure.RateLimiterProperties
                declaredLimiters = rateLimiterProperties == null
                        ? null : rateLimiterProperties.getIfAvailable();
        if (shared != null && declaredLimiters != null) {
            java.util.Set<String> surfaceNames = new java.util.TreeSet<>();
            for (java.util.Map.Entry<String, ?> entry
                    : declaredLimiters.getConfigs().entrySet()) {
                collectBudgetSurface(PER_CALLER_CONFIG, entry.getKey(), surfaceNames);
                collectBudgetSurface(PER_ACCOUNT_CONFIG, entry.getKey(), surfaceNames);
            }
            // The 2026-11-08 #806 round-9 review (Minor · Functional
            // Correctness, verified against the INSTALLED resilience4j
            // 2.4.0 bytecode): RateLimiterRegistry.getConfiguration sees
            // ONLY the `configs` map — the auto-configuration builds the
            // registry's named configurations from `configs` alone
            // (createRateLimiterRegistry), and an `instances` entry
            // initializes its limiter through rateLimiter(name, config),
            // whose AbstractRegistry.computeIfAbsent path stores the ENTRY
            // but never the CONFIG (the configurations map is written only
            // by addConfiguration). A budget-family key declared under
            // `instances` is therefore silently INVISIBLE to both the
            // runtime resolveConfig and this startup validation — the
            // guard would run the family-wide values while the operator
            // believes the override applies. Fail fast at startup: the
            // budget families' keys belong under `configs` (and a silent
            // misconfiguration is the exact always-evict class the
            // max-tracked-callers check exists to prevent).
            for (java.util.Map.Entry<String, ?> entry
                    : declaredLimiters.getInstances().entrySet()) {
                requireNotAnInstancesBudgetEntry(PER_CALLER_CONFIG, entry.getKey());
                requireNotAnInstancesBudgetEntry(PER_ACCOUNT_CONFIG, entry.getKey());
            }
            for (String surface : surfaceNames) {
                // Every override in these families is a fail-fast budget:
                // a queued 5s wait would hold the request thread against
                // the documented zero-timeout contract.
                RateLimiterConfig surfaceCaller = shared
                        .getConfiguration(PER_CALLER_CONFIG + "-" + surface)
                        .orElse(callerConfig);
                RateLimiterConfig surfaceAccount = shared
                        .getConfiguration(PER_ACCOUNT_CONFIG + "-" + surface)
                        .orElse(accountConfig);
                requireZeroTimeout(PER_CALLER_CONFIG + "-" + surface, surfaceCaller);
                requireZeroTimeout(PER_ACCOUNT_CONFIG + "-" + surface, surfaceAccount);
                requireWindowAwareAccountHeadroom(
                        PER_CALLER_CONFIG + "-" + surface, surfaceCaller, surfaceAccount);
            }
        }
        // The wave-3 review (Major · Stability): dynamically keyed
        // instances in the AUTO-CONFIGURED registry would grow without
        // bound — the official builder path with a bounded store keeps
        // the per-caller budget AND the bounded memory. The eviction
        // tradeoff is documented: an address evicted at capacity re-earns
        // a fresh budget on its next request; the outer per-JVM ceilings
        // still bound the aggregate.
        // The 2026-11-05 #806 round-3 review (Major · Security & Privacy):
        // the two key families (per-address and per-account) previously
        // shared ONE bounded store — at a small max-tracked-callers the
        // address keys and the account keys evicted EACH OTHER (at 1: an
        // alternating pair starts both budgets fresh every request and NO
        // limit applies). Each family now carries its OWN bounded store.
        this.perCallerRegistry = RateLimiterRegistry.custom()
                .withRegistryStore(new BoundedLruStore<>(maxTrackedCallers))
                .addRateLimiterConfig(PER_CALLER_CONFIG, DEFAULT_PER_CALLER_CONFIG)
                .build();
        this.perAccountRegistry = RateLimiterRegistry.custom()
                .withRegistryStore(new BoundedLruStore<>(maxTrackedCallers))
                .addRateLimiterConfig(PER_ACCOUNT_CONFIG, DEFAULT_PER_ACCOUNT_CONFIG)
                .build();
    }

    /**
     * The window-aware lockout invariant (the 2026-11-05 #806 round-3
     * review, Major · Security &amp; Privacy): the account budget must
     * strictly exceed the caller budget AND the MOST one address can
     * obtain within one account window (callerLimit × ceil(accountWindow
     * / callerWindow)) must stay STRICTLY BELOW the account budget —
     * otherwise one address alone exhausts an account's whole budget and
     * locks the owner out. The ceiling comparison is >= on purpose (the
     * 2026-11-08 #806 round-9 review): a ceiling EQUAL to the budget
     * still leaves the owner zero headroom — one address alone consumes
     * every permit of the account window. Shared by the family-wide
     * startup check and every per-surface override check (the
     * 2026-11-06 #806 round review).
     */
    private static void requireWindowAwareAccountHeadroom(
            String configName, RateLimiterConfig callerConfig, RateLimiterConfig accountConfig) {
        long accountWindowMs = accountConfig.getLimitRefreshPeriod().toMillis();
        long callerWindowMs = Math.max(1, callerConfig.getLimitRefreshPeriod().toMillis());
        long windowsPerAccountWindow =
                (accountWindowMs + callerWindowMs - 1) / callerWindowMs; // ceil
        long callerCeilingPerAccountWindow =
                callerConfig.getLimitForPeriod() * windowsPerAccountWindow;
        if (accountConfig.getLimitForPeriod() <= callerConfig.getLimitForPeriod()
                || callerCeilingPerAccountWindow >= accountConfig.getLimitForPeriod()) {
            throw new IllegalArgumentException(
                    "marketplace.graphql.per-caller-rate-limit: the " + configName
                            + " pair violates the lockout invariant — the account budget ("
                            + accountConfig.getLimitForPeriod() + "/"
                            + accountConfig.getLimitRefreshPeriod() + ") must strictly exceed the "
                            + "caller budget (" + callerConfig.getLimitForPeriod() + "/"
                            + callerConfig.getLimitRefreshPeriod() + ") AND the per-caller ceiling "
                            + "within one account window (" + callerCeilingPerAccountWindow
                            + ") — otherwise one address alone exhausts an account's whole budget");
        }
    }

    /**
     * The fail-fast timeout contract of the keyed budget families: every
     * entry (family-wide or per-surface override) queues for ZERO — a
     * positive timeout would hold the request thread for the wait against
     * the documented reject-immediately semantics (the 2026-11-06 #806
     * round review: an override riding {@code base-config: default}
     * inherits the DEFAULT family's 5s queue).
     */
    private static void requireZeroTimeout(String configName, RateLimiterConfig config) {
        if (!config.getTimeoutDuration().isZero()) {
            throw new IllegalArgumentException(
                    "marketplace.graphql.per-caller-rate-limit: " + configName
                            + " must declare timeout-duration: 0 (fail-fast), got "
                            + config.getTimeoutDuration());
        }
    }

    /**
     * Collects the surface name of a {@code <family>-<surface>} override
     * entry (e.g. {@code perCallerGrant-graphqlCheckoutMutation} yields
     * {@code graphqlCheckoutMutation}) — the names the per-surface
     * startup validation iterates.
     */
    private static void collectBudgetSurface(String family, String entryName,
                                             java.util.Set<String> surfaces) {
        if (entryName != null && entryName.startsWith(family + "-")
                && entryName.length() > family.length() + 1) {
            surfaces.add(entryName.substring(family.length() + 1));
        }
    }

    /**
     * The instances-map rejection (the 2026-11-08 #806 round-9 review —
     * see the constructor's full evidence block): a budget-family key
     * under {@code resilience4j.rateLimiter.instances} is invisible to
     * {@code RateLimiterRegistry.getConfiguration}, so the guard would
     * silently ignore the operator's override. The startup fails fast —
     * the override belongs under {@code configs}.
     */
    private static void requireNotAnInstancesBudgetEntry(String family, String instancesKey) {
        if (instancesKey != null
                && (instancesKey.equals(family) || instancesKey.startsWith(family + "-"))) {
            throw new IllegalArgumentException(
                    "marketplace.graphql.per-caller-rate-limit: the budget entry '" + instancesKey
                            + "' is declared under resilience4j.rateLimiter.instances — budget"
                            + " family overrides are visible to this guard ONLY under configs"
                            + " (the registry's getConfiguration reads configs entries alone;"
                            + " an instances entry initializes its limiter but never registers"
                            + " the config, verified against resilience4j 2.4.0). Move the"
                            + " entry to configs");
        }
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
        acquirePermitForKey(surface, "-caller:" + remoteAddressKey(environment),
                PER_CALLER_CONFIG, DEFAULT_PER_CALLER_CONFIG, perCallerRegistry);
    }

    /**
     * Consumes one permit of the ACCOUNT's own budget for the surface —
     * the ANONYMOUS email-argument shape (the wave-4 review, Minor ·
     * Security &amp; Privacy): the address key is alone, so an attacker
     * distributing attempts across many addresses faces no limit per
     * victim account (credential stuffing, mailbox flooding). The key is
     * the SHA-256 of the NORMALIZED email argument (trimmed, then
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
     * <p><b>This shape is for ANONYMOUS roots only</b> (the
     * 2026-11-08 #806 round-9 review, Major · Security &amp; Privacy):
     * the email ARGUMENT is unverified input, so consuming the account
     * budget from it BEFORE the root's own work lets an attacker with a
     * few addresses burn the victim's account budget and lock the
     * victim's own legitimate request out before any verification runs.
     * An anonymous root (e.g. the password-reset request) has NO
     * verification to wait for — the email argument is the honest key
     * there, and the bounded send is the defense itself. An AUTHENTICATED
     * surface (e.g. the password grant) consumes its account budget
     * through {@link #acquireAccountPermitForKey} AFTER authentication —
     * keyed by the authenticated principal, never by the unverified
     * argument.</p>
     *
     * <p><b>The account budget rides its OWN {@code perAccountGrant}
     * configuration, NEVER the address budget's {@code perCallerGrant}
     * (the wave-4 #806 review's second-cycle Major · Security &amp; Privacy
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
        // The 2026-11-06 #806 round-5 review (Minor · Functional
        // Correctness): the CONFIG override name is the RAW surface
        // (perAccountGrant-<surface>) — exactly the pair the startup
        // validation checks — while the "-account" suffix stays on the
        // INSTANCE key only (the account family's own namespacing).
        acquirePermitForKey(surface, "-account:" + accountKey,
                PER_ACCOUNT_CONFIG, DEFAULT_PER_ACCOUNT_CONFIG, perAccountRegistry);
    }

    /**
     * Consumes one permit of the ACCOUNT's own budget for the surface —
     * the AUTHENTICATED shape (the 2026-11-08 #806 round-9 review, Major
     * · Security &amp; Privacy): the key is the SHA-256 of the NORMALIZED
     * email the owning use case passes AFTER its own verification (e.g.
     * the password grant consumes this only once the password check
     * succeeded — the authenticated principal, never the unverified
     * email argument). Failed verification attempts therefore never burn
     * the account's budget: an attacker distributing attempts across
     * many addresses can no longer lock the victim's CORRECT sign-in out
     * before the password check — the exact pre-authentication lockout
     * the review demonstrated — while the per-ADDRESS budget and the
     * shared platform ceiling still cap the attempt flood itself, and
     * successful grants stay capped per-account through this method.
     *
     * <p>Same key derivation and same {@code perAccountGrant}
     * configuration as the anonymous shape — one account, one budget,
     * both consumption points keyed identically (the authenticated
     * principal IS the account's normalized email).</p>
     *
     * @param email  the AUTHENTICATED account identifier (the principal
     *               name — verified by the caller before this call)
     * @param surface the surface name — the account instances ride
     *                {@code <surface>-account:<sha256>} keys with the
     *                SEPARATE {@code perAccountGrant} config
     * @throws RequestNotPermitted the account's own budget is exhausted
     */
    public void acquireAccountPermitForKey(String email, String surface) {
        // Null only in a context that never authenticates a principal —
        // nothing to budget (the same fail-safe direction the anonymous
        // shape takes on a missing argument).
        String accountKey = accountKeyOfEmail(email);
        if (accountKey == null) {
            return;
        }
        acquirePermitForKey(surface, "-account:" + accountKey,
                PER_ACCOUNT_CONFIG, DEFAULT_PER_ACCOUNT_CONFIG, perAccountRegistry);
    }

    /**
     * Resolves the budget config: the SURFACE-specific entry
     * ({@code <configName>-<surface>}, e.g.
     * {@code perCallerGrant-graphqlCheckoutMutation}) first, then the
     * family-wide {@code configName} (perCallerGrant / perAccountGrant),
     * then the programmatic fallback — the registry's own configuration
     * lookup API, never a second config path.
     */
    private RateLimiterConfig resolveConfig(String configName, String surface, RateLimiterConfig fallback) {
        if (sharedRegistry.getIfAvailable() == null) {
            return fallback;
        }
        var registry = sharedRegistry.getObject();
        return registry.getConfiguration(configName + "-" + surface)
                .or(() -> registry.getConfiguration(configName))
                .orElse(fallback);
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
    private void acquirePermitForKey(String surface, String keySuffix,
                                     String configName, RateLimiterConfig fallback,
                                     RateLimiterRegistry familyRegistry) {
        // The keyed budgets ride the yml's configs when the shared registry
        // declares them (the full application context); the guard's own
        // bounded registry owns the instances either way.
        //
        // The 2026-11-06 #806 round (Minor · Stability & Availability): a
        // surface may carry its OWN grant config — {@code <family
        // config>-<surface>} (e.g. perCallerGrant-graphqlCheckoutMutation)
        // — resolved FIRST through the same official registry config
        // lookup, falling back to the family-wide config. The high-volume
        // checkout surface (the platform's highest-frequency writes, per
        // the aggregate-ceiling reasoning) and the admin order surface
        // join the per-caller model with this override point, so a
        // deployment can size one surface's per-caller grant without
        // touching the anonymous account roots' tuned 10/min — and the
        // test profile relaxes exactly those two surfaces without
        // weakening the anonymous-flood proof.
        RateLimiterConfig config = resolveConfig(configName, surface, fallback);
        RateLimiter limiter = familyRegistry.rateLimiter(
                surface + keySuffix, config);
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
     * ({@code input.email} / {@code input.emailAddress}). The
     * normalization itself is {@link #accountKeyOfEmail} — the ONE
     * derivation both consumption shapes share.
     */
    private static String accountKeyOf(DataFetchingEnvironment environment) {
        String email = argumentEmail(environment);
        if (email == null || email.isBlank()) {
            return null;
        }
        return accountKeyOfEmail(email);
    }

    /**
     * The account budget key of an EMAIL VALUE (the shared derivation of
     * both consumption shapes): the SHA-256 hex of the normalized address
     * (trimmed, then lower-cased with {@link Locale#ROOT} — the identity
     * owner's own account-lookup normalization). Null for a blank value.
     * The authenticated shape passes the verified principal; the
     * anonymous shape passes the email argument — one normalization, one
     * key space, one budget per account.
     */
    static String accountKeyOfEmail(String email) {
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
     * <p><b>Internal path:</b> the socket PEER is the private-mesh address
     * (RFC 1918/ULA — see {@link #isRailwayInternalPath}); on the Railway
     * private mesh it is e.g. {@code app-java-v3.railway.internal}'s mesh
     * peer (the edge BFF's documented relay target). The key is the socket
     * peer; forwarded headers are not consulted at all (the platform does
     * not sanitize headers on this path, so none of them can be trusted).
     * The Host header alone never settles this path — a private-mesh
     * caller rotating a public-looking Host must not reach the
     * {@code X-Real-IP} branch (the 2026-11-08 #806 round-9 review).</p>
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
                return budgetKeyOf(realIp);
            }
            return request.getRemoteAddr();
        }
        return null;
    }

    /**
     * Whether the request arrived over the Railway PRIVATE mesh.
     *
     * <p><b>The PRIMARY discriminator is the socket PEER address</b> (the
     * 2026-11-08 #806 round-9 review, Major · Security &amp; Privacy):
     * the private-mesh address standard is RFC 1918 (10/8, 172.16/12,
     * 192.168/16) plus IPv6 ULA (fc00::/7) — the same standard the
     * deployment reference and {@link #budgetKeyOf}'s mesh note document.
     * A private-mesh caller cannot rotate its socket peer (the mesh
     * routes the caller's own address), so a caller inside the trust
     * boundary is classified internal NO MATTER which Host header it
     * sends — the previous Host-only rule let a mesh caller with a
     * public-looking Host reach the {@code X-Real-IP} branch and rotate
     * fresh budgets through the self-controlled header. The Railway HTTP
     * edge's own agents connect from the CGNAT space 100.64.0.0/10 —
     * outside RFC 1918/ULA — so genuine public edge traffic never
     * misclassifies here.</p>
     *
     * <p><b>The private-DNS Host suffix stays as the belt</b> (documented
     * belt-and-braces, never the sole key): the raw Host header as
     * delivered to the container, port-stripped per the Servlet spec,
     * ending with {@code .railway.internal} (the platform's documented
     * private-DNS suffix; this deployment's internal relay target is
     * {@code app-java-v3.railway.internal}). Compared case-insensitively
     * under {@link Locale#ROOT} (hostnames are case-insensitive per RFC
     * 3986 §3.2.2; the wave-4 review taught never to case-map without an
     * explicit locale). A Host-only match grants NOTHING the peer rule
     * would not: this branch keys the socket peer and reads no forwarded
     * header, so a public-edge client spoofing the internal Host merely
     * joins the edge agent's own peer-keyed budget — no fresh budgets,
     * no bypass.</p>
     */
    private static boolean isRailwayInternalPath(
            jakarta.servlet.http.HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer != null && isPrivateMeshAddress(peer)) {
            return true;
        }
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
     * The budget key for a VALIDATED IP literal (the wave-7 review's
     * nitpick, Trivial · Security & Privacy): IPv4 literals pass through
     * whole (a /32 IS the caller's own address), while IPv6 literals
     * collapse to their <b>/64 prefix</b> — the smallest allocation an end
     * site receives (RFC 4291's subnet boundary). Without the collapse, a
     * single IPv6 client rotating SLAAC or privacy-extension addresses
     * INSIDE its own /64 mints a fresh budget per address — splitting the
     * 10-per-minute allowance into N independent budgets — and churns the
     * bounded caller store, evicting honest callers' entries.
     *
     * <p>The parse is DNS-free by construction: the input has already
     * passed {@link #isIpLiteral} (a hostname-shaped value never reaches
     * this method), and the JDK resolves digit/colon-shaped literals
     * locally. The internal path's socket peer needs no normalization —
     * the mesh addresses are RFC 1918/ULA, one stable peer per edge
     * process.</p>
     */
    private static String budgetKeyOf(String ipLiteral) {
        try {
            byte[] octets = java.net.InetAddress.getByName(ipLiteral).getAddress();
            if (octets.length != 16) {
                // The 2026-11-06 #806 round review (Minor · Functional
                // Correctness): the CANONICAL dotted form, never the raw
                // literal — the JDK collapses an IPv4-mapped IPv6 literal
                // (::ffff:1.2.3.4) to the same 4-byte array as 1.2.3.4,
                // and the raw text would split ONE machine's address into
                // TWO budget keys (the mapped form and the plain form).
                // The canonical render unifies them.
                return java.net.InetAddress.getByAddress(octets).getHostAddress();
            }
            byte[] prefix = octets.clone();
            java.util.Arrays.fill(prefix, 8, 16, (byte) 0);
            return java.net.InetAddress.getByAddress(prefix).getHostAddress();
        } catch (java.net.UnknownHostException unparseable) {
            return ipLiteral; // unreachable for a validated literal; fail to the literal, never DNS
        }
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
     * The private-mesh matchers behind {@link #isPrivateMeshAddress} (the
     * 2026-11-08 #806 round-9 review): the project's documented
     * private-network address standard — RFC 1918's three blocks plus
     * IPv6 Unique Local Addresses (fc00::/7, RFC 4193) — through the SAME
     * official Spring Security address machinery the literal gate uses.
     * The Railway HTTP edge's agents connect from 100.64.0.0/10 (RFC
     * 6598 shared/CGNAT space — outside every matcher here), so the
     * public edge never matches.
     */
    private static final org.springframework.security.util.matcher.InetAddressMatcher
            PRIVATE_RFC1918_10 =
            org.springframework.security.util.matcher.InetAddressMatchers.fromIpAddress(
                    "10.0.0.0/8");
    private static final org.springframework.security.util.matcher.InetAddressMatcher
            PRIVATE_RFC1918_172 =
            org.springframework.security.util.matcher.InetAddressMatchers.fromIpAddress(
                    "172.16.0.0/12");
    private static final org.springframework.security.util.matcher.InetAddressMatcher
            PRIVATE_RFC1918_192 =
            org.springframework.security.util.matcher.InetAddressMatchers.fromIpAddress(
                    "192.168.0.0/16");
    private static final org.springframework.security.util.matcher.InetAddressMatcher
            PRIVATE_ULA_FC00 =
            org.springframework.security.util.matcher.InetAddressMatchers.fromIpAddress(
                    "fc00::/7");

    /**
     * Whether the address is a PRIVATE-MESH literal — RFC 1918 or ULA —
     * the internal-path discriminator {@link #isRailwayInternalPath} keys
     * on (the 2026-11-08 #806 round-9 review). Same DNS-free validation
     * contract as {@link #isIpLiteral}: Spring Security's parser rejects
     * hostname-shaped values before any resolution, so a non-literal
     * (tests, odd containers) simply answers {@code false} and never
     * throws.
     */
    private static boolean isPrivateMeshAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return PRIVATE_RFC1918_10.matches(value) || PRIVATE_RFC1918_172.matches(value)
                    || PRIVATE_RFC1918_192.matches(value) || PRIVATE_ULA_FC00.matches(value);
        } catch (IllegalArgumentException notALiteral) {
            return false;
        }
    }

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
