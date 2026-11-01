package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsRequestData;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wave-4 #806 review's second cycle (Major · Security & Privacy): the
 * ACCOUNT-keyed budget must be SEPARATE from the ADDRESS budget. Riding the
 * address budget's {@code perCallerGrant} (10/min) let ONE unauthenticated
 * address burn the VICTIM's account budget — ten {@code tokenCreate} calls
 * with the victim's email — after which the victim's own login from their
 * OWN address answered {@code RequestNotPermitted} from the account budget:
 * a login lockout administered remotely. {@code perAccountGrant} strictly
 * exceeds the address budget, so the review's exact scenario must pass:
 * ten requests from one address, then a request for the SAME account from
 * a second address is NOT rejected.
 */
@SuppressWarnings("unchecked")
class PerCallerRateLimitGuardTest {

    private static final String SURFACE = "graphqlTokenMutation";
    private static final String VICTIM_EMAIL = "victim@example.com";

    /**
     * The guard with NO shared registry (the slice semantics): the
     * programmatic defaults apply — perCallerGrant 10/min and the SEPARATE
     * perAccountGrant 30/min.
     */
    private PerCallerRateLimitGuard guardWithDefaults() {
        ObjectProvider<RateLimiterRegistry> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        return new PerCallerRateLimitGuard(absent, 1000);
    }

    /**
     * The guard with a shared registry declaring BOTH families — the
     * account family at a DIFFERENT limit than the address family, so the
     * test proves the account budget reads its OWN {@code perAccountGrant}
     * configuration, never the address budget's {@code perCallerGrant}.
     */
    private PerCallerRateLimitGuard guardWithSharedConfigs(int addressLimit, int accountLimit) {
        RateLimiterRegistry shared = RateLimiterRegistry.custom()
                .addRateLimiterConfig(PerCallerRateLimitGuard.PER_CALLER_CONFIG,
                        config(addressLimit))
                .addRateLimiterConfig(PerCallerRateLimitGuard.PER_ACCOUNT_CONFIG,
                        config(accountLimit))
                .build();
        ObjectProvider<RateLimiterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(shared);
        when(provider.getObject()).thenReturn(shared);
        return new PerCallerRateLimitGuard(provider, 1000);
    }

    private static RateLimiterConfig config(int limitForPeriod) {
        return RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    /**
     * A data fetcher environment whose request arrives from the given
     * socket address (no X-Forwarded-For — the root request's peer)
     * and carries the email argument the account key derives from.
     * Wired through the DGS public API: the {@link DgsContext}
     * consumer puts the context into the {@link GraphQLContext}
     * exactly the way the DGS framework's own context builder does.
     */
    private static DataFetchingEnvironment environment(String remoteAddress, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return environmentFor(request, email);
    }

    /**
     * A data fetcher environment whose request arrives over the RAILWAY
     * INTERNAL mesh (the wave-6 #806 review's two-path policy): the root
     * request's Host is the edge BFF's documented relay target
     * {@code app-java-v3.railway.internal}, the socket peer is the edge
     * service's mesh address, and the caller-controlled forwarding
     * headers ({@code X-Forwarded-For}, {@code X-Real-IP}) carry whatever
     * the relaying chain chose to send — the platform does not sanitize
     * headers on this path.
     */
    private static DataFetchingEnvironment internalMeshEnvironment(
            String forgedForwardedLine, String forgedRealIp, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.setServerName("app-java-v3.railway.internal");
        request.addHeader("X-Forwarded-For", forgedForwardedLine);
        request.addHeader("X-Real-IP", forgedRealIp);
        return environmentFor(request, email);
    }

    /**
     * A data fetcher environment whose request arrives over the PUBLIC
     * Railway edge path: the Host is the public domain (no
     * {@code .railway.internal} suffix), the socket peer is the edge
     * proxy itself, the edge-OWNED {@code X-Real-IP} header may arrive
     * on two lines (the third-pass #806 review's multi-line hardening
     * now applied to the real key) and a caller-supplied
     * {@code X-Forwarded-For} line is present — which the platform
     * replaces on the public path and the policy must therefore ignore.
     */
    private static DataFetchingEnvironment publicEdgeEnvironment(
            String firstRealIpLine, String lastRealIpLine,
            String callerSuppliedForwardedLine, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("100.64.0.7");
        request.setServerName("app-java-v3-production.up.railway.app");
        if (firstRealIpLine != null) {
            request.addHeader("X-Real-IP", firstRealIpLine);
        }
        if (lastRealIpLine != null) {
            request.addHeader("X-Real-IP", lastRealIpLine);
        }
        request.addHeader("X-Forwarded-For", callerSuppliedForwardedLine);
        return environmentFor(request, email);
    }

    private static DataFetchingEnvironment environmentFor(
            MockHttpServletRequest request, String email) {
        DgsRequestData requestData = new DgsWebMvcRequestData(
                Map.of(), HttpHeaders.EMPTY, new ServletWebRequest(request));
        DgsContext dgsContext = new DgsContext(null, requestData);
        GraphQLContext.Builder contextBuilder = GraphQLContext.newContext();
        dgsContext.accept(contextBuilder);
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(contextBuilder.build())
                .arguments(Map.of("email", email))
                .build();
    }

    @Test
    void theSameAccountFromASecondAddressIsNotRejectedAfterOneAddressExhaustsItsOwnBudget() {
        // The review's exact scenario: ten requests from ONE address (the
        // address budget 10/min is exhausted AND the account budget has
        // consumed ten of its own perAccountGrant), then a request for the
        // SAME account from ANOTHER address must NOT be rejected — the
        // fresh address budget passes and the account budget still holds
        // headroom because perAccountGrant strictly exceeds perCallerGrant.
        PerCallerRateLimitGuard guard = guardWithDefaults();
        DataFetchingEnvironment attacker = environment("203.0.113.10", VICTIM_EMAIL);
        for (int attempt = 0; attempt < 10; attempt++) {
            guard.acquirePermit(attacker, SURFACE);
            guard.acquireAccountPermit(attacker, SURFACE);
        }

        // The attacker's own ADDRESS budget is exhausted — the flood stays
        // bounded exactly as before.
        assertThatThrownBy(() -> guard.acquirePermit(attacker, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);

        // The VICTIM logging in from their own address: a fresh address
        // budget AND an account budget that still holds headroom — NOT
        // rejected. This is the regression the review demonstrated: under
        // the shared perCallerGrant this exact call answered
        // RequestNotPermitted and locked the victim out of their login.
        DataFetchingEnvironment victim = environment("198.51.100.7", VICTIM_EMAIL);
        assertThatCode(() -> {
            guard.acquirePermit(victim, SURFACE);
            guard.acquireAccountPermit(victim, SURFACE);
        }).doesNotThrowAnyException();
    }

    @Test
    void theAccountBudgetReadsItsOwnPerAccountGrantConfigAndStillCapsStuffing() {
        // The account family answers to perAccountGrant (20 here), NEVER
        // to the address family's perCallerGrant (10): after ten hits
        // through one address, a second address still passes account
        // permits until the ACCOUNT's own 20 are consumed — and the 21st
        // rejects. The distributed credential-stuffing cap stays intact;
        // only the single-address lockout is gone.
        PerCallerRateLimitGuard guard = guardWithSharedConfigs(10, 20);
        DataFetchingEnvironment first = environment("203.0.113.10", VICTIM_EMAIL);
        DataFetchingEnvironment second = environment("198.51.100.7", VICTIM_EMAIL);

        for (int attempt = 0; attempt < 10; attempt++) {
            guard.acquirePermit(first, SURFACE);
            guard.acquireAccountPermit(first, SURFACE);
        }

        // The account budget is NOT the address budget: under the old
        // shared config these ten follow-ups from the second address
        // would already have rejected at the tenth.
        int acceptedFromSecondAddress = 0;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                guard.acquireAccountPermit(second, SURFACE);
                acceptedFromSecondAddress++;
            } catch (RequestNotPermitted expectedAtTheCap) {
                break;
            }
        }

        if (acceptedFromSecondAddress != 10) {
            // Twenty total account permits were available; the second
            // address must have consumed exactly the remaining ten before
            // the cap rejected — any earlier rejection means the account
            // family is still reading the address family's config.
            fail("the account budget rejected after %d additional permits; expected exactly 10",
                    acceptedFromSecondAddress);
        }

        // The account's OWN cap: the 21st account permit rejects — the
        // stuffing defense the account budget exists for.
        assertThatThrownBy(() -> guard.acquireAccountPermit(second, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);
    }

    /**
     * The third-pass #806 review (Minor · Stability): a
     * max-tracked-callers of 0 or below must fail the guard's
     * construction — under the old store an always-evicting LRU handed
     * every request a FRESH limiter and rate limiting was silently
     * disabled.
     */
    @Test
    void maxTrackedCallersBelowOneIsRejectedAtConstruction() {
        ObjectProvider<RateLimiterRegistry> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> new PerCallerRateLimitGuard(absent, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-tracked-callers");
        assertThatThrownBy(() -> new PerCallerRateLimitGuard(absent, -3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-tracked-callers");
    }

    /**
     * The wave-6 #806 review (Minor · Functional Correctness), internal
     * leg: the platform contract (staff-confirmed, docs.railway.com) is
     * that requests over {@code *.railway.internal} SKIP the public edge
     * and its header sanitization, so forwarded headers there are
     * caller-influenceable and must be read NOWHERE — the key is the
     * socket peer (the edge BFF's mesh address). Ten requests, each with
     * a DIFFERENT forged {@code X-Forwarded-For} line AND a DIFFERENT
     * forged {@code X-Real-IP}, must exhaust ONE budget (the socket
     * peer's) — the eleventh rejects. Under the previous rightmost-XFF
     * key every one of these requests would have opened a FRESH budget
     * and sailed through.
     */
    @Test
    void forgedForwardedHeadersOnTheInternalMeshNeverSplitTheBudget() {
        PerCallerRateLimitGuard guard = guardWithDefaults();
        for (int attempt = 0; attempt < 10; attempt++) {
            DataFetchingEnvironment forged = internalMeshEnvironment(
                    "203.0.113." + attempt + ", 198.51.100.1",
                    "203.0.113." + attempt,
                    VICTIM_EMAIL);
            guard.acquirePermit(forged, SURFACE);
        }
        // The eleventh forged request: under a header-derived key it
        // would open a FRESH budget ("203.0.113.10" was never used);
        // under the socket-peer key the mesh caller's single budget is
        // exhausted and the request rejects.
        DataFetchingEnvironment eleventh = internalMeshEnvironment(
                "203.0.113.10, 198.51.100.1", "203.0.113.10", VICTIM_EMAIL);
        assertThatThrownBy(() -> guard.acquirePermit(eleventh, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);
    }

    /**
     * The wave-6 #806 review (Minor · Functional Correctness), public
     * leg: the key is the edge-owned {@code X-Real-IP} — Railway's edge
     * overwrites it on every public request and documents it as the
     * client's remote IP — taken from the LAST header line (the
     * multi-line hardening), with caller-supplied {@code
     * X-Forwarded-For} ignored entirely (the platform replaces it on
     * the public path and does not document a fixed hop count, so its
     * rightmost entry is no identity source). Ten requests with a
     * stable edge {@code X-Real-IP} but a DIFFERENT caller-supplied XFF
     * line each exhaust ONE budget — the eleventh rejects.
     */
    @Test
    void thePublicPathKeysOnTheEdgeSetRealIpAndIgnoresForwardedFor() {
        PerCallerRateLimitGuard guard = guardWithDefaults();
        for (int attempt = 0; attempt < 10; attempt++) {
            DataFetchingEnvironment rotated = publicEdgeEnvironment(
                    "rotated-spoofed-line", "198.51.100.23",
                    "203.0.113." + attempt, VICTIM_EMAIL);
            guard.acquirePermit(rotated, SURFACE);
        }
        // Same stable edge X-Real-IP, an eleventh rotated XFF line: the
        // budget keyed on 198.51.100.23 is exhausted — rejected. Under a
        // caller-influenceable XFF key every request would have opened a
        // FRESH budget and been accepted.
        DataFetchingEnvironment eleventh = publicEdgeEnvironment(
                "rotated-spoofed-line", "198.51.100.23",
                "203.0.113.10", VICTIM_EMAIL);
        assertThatThrownBy(() -> guard.acquirePermit(eleventh, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);
    }

    /**
     * The wave-6 #806 review's demand that the header value be verified
     * as an IP literal BEFORE use: a non-literal {@code X-Real-IP}
     * (here: a hostname-shaped value) can never key a budget — the key
     * falls back to the socket peer. Ten requests, each with a
     * DIFFERENT hostname-shaped {@code X-Real-IP}, must exhaust ONE
     * budget (the socket peer's) — the eleventh rejects; and a VALID
     * literal arriving afterwards from the same socket peer keys its OWN
     * budget (accepted), proving the non-literals never became keys.
     */
    @Test
    void aNonLiteralRealIpCanNeverKeyTheBudget() {
        PerCallerRateLimitGuard guard = guardWithDefaults();
        for (int attempt = 0; attempt < 10; attempt++) {
            DataFetchingEnvironment junk = publicEdgeEnvironment(
                    null, "attacker-" + attempt + ".evil.example",
                    "203.0.113.9", VICTIM_EMAIL);
            guard.acquirePermit(junk, SURFACE);
        }
        // The eleventh junk-keyed request: keyed on the socket peer
        // (100.64.0.7), whose budget the ten requests exhausted.
        DataFetchingEnvironment eleventh = publicEdgeEnvironment(
                null, "attacker-10.evil.example", "203.0.113.9", VICTIM_EMAIL);
        assertThatThrownBy(() -> guard.acquirePermit(eleventh, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);

        // A VALID literal from the same socket peer keys its OWN budget:
        // accepted — the non-literals were never used as keys.
        DataFetchingEnvironment validLiteral = publicEdgeEnvironment(
                null, "203.0.113.77", "203.0.113.9", VICTIM_EMAIL);
        assertThatCode(() -> guard.acquirePermit(validLiteral, SURFACE))
                .doesNotThrowAnyException();
    }

    /**
     * The IP-literal gate accepts BOTH families (the reviewer's
     * "تحقّق من أن القيمة عنوان IP حرفي" — verify the value is an IP
     * literal), and the wave-7 review's nitpick (Trivial · Security &
     * Privacy) keys the IPv6 budget on the /64 PREFIX, not the whole
     * literal: a client rotating addresses INSIDE its own /64 (SLAAC /
     * privacy extensions) meets ONE budget — ten requests through
     * {@code 2001:db8::1} exhaust it, and the eleventh REJECTS even from
     * a different address of that same /64 — while a literal from a
     * DIFFERENT /64 keys a fresh budget (accepted). Without the
     * normalization the rotation split the allowance into per-address
     * budgets and churned the bounded caller store.
     */
    @Test
    void anIpv6LiteralKeysItsSlash64PrefixBudget() {
        PerCallerRateLimitGuard guard = guardWithDefaults();
        for (int attempt = 0; attempt < 10; attempt++) {
            DataFetchingEnvironment v6 = publicEdgeEnvironment(
                    null, "2001:db8::1", "203.0.113.9", VICTIM_EMAIL);
            guard.acquirePermit(v6, SURFACE);
        }
        // Rotation INSIDE the exhausted /64: no fresh budget — the
        // eleventh request rejects from a different address of that /64.
        DataFetchingEnvironment rotated = publicEdgeEnvironment(
                null, "2001:db8::2", "203.0.113.9", VICTIM_EMAIL);
        assertThatThrownBy(() -> guard.acquirePermit(rotated, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);

        // A DIFFERENT /64: its own fresh budget — accepted.
        DataFetchingEnvironment otherPrefix = publicEdgeEnvironment(
                null, "2001:db8:0:1::1", "203.0.113.9", VICTIM_EMAIL);
        assertThatCode(() -> guard.acquirePermit(otherPrefix, SURFACE))
                .doesNotThrowAnyException();
    }

    /**
     * The third-pass #806 review (Minor · Functional Correctness): the
     * account key's normalization must be locale-invariant. Under the
     * Turkish default locale a {@code toLowerCase()} without a locale
     * maps {@code I} to the dotless {@code ı}, splitting one account's
     * budget across two keys (INFO@x.com vs info@x.com) and letting an
     * attacker double its stuffing budget by case-mixing the email.
     * {@link Locale#ROOT} — the identity owner's own normalization —
     * keeps both forms on ONE budget.
     */
    @Test
    void theAccountBudgetKeyIsLocaleInvariantUnderTheJvmDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            PerCallerRateLimitGuard guard = guardWithDefaults();
            // Exhaust the account budget (perAccountGrant 30/min) with
            // the uppercase form — I is the locale-sensitive character.
            for (int attempt = 0; attempt < 30; attempt++) {
                guard.acquireAccountPermit(
                        environment("203.0.113.10", "INFO@EXAMPLE.COM"), SURFACE);
            }
            // The canonical lowercase form must share the SAME budget
            // (rejected): a default-locale normalization would have
            // opened a fresh budget for it and accepted.
            assertThatThrownBy(() -> guard.acquireAccountPermit(
                    environment("198.51.100.7", "info@example.com"), SURFACE))
                    .isInstanceOf(RequestNotPermitted.class);
        } finally {
            Locale.setDefault(original);
        }
    }
}
