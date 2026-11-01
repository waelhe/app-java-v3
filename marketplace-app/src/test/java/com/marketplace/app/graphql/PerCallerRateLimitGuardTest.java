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
     * A data fetcher environment whose request carries the
     * X-Forwarded-For header on TWO lines (the third-pass #806 review's
     * multi-line scenario): the client-controlled first line and the
     * trusted edge's appended last line. The servlet container peer is
     * the edge itself.
     */
    private static DataFetchingEnvironment forwardedEnvironment(
            String firstForwardedLine, String lastForwardedLine, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", firstForwardedLine);
        request.addHeader("X-Forwarded-For", lastForwardedLine);
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
     * The third-pass #806 review (Minor · Security & Privacy):
     * {@code getHeader} answers only the FIRST line of a multi-line
     * X-Forwarded-For header — a caller rotating the client-supplied
     * first line would rotate its BUDGET KEY per request and out-run
     * the per-caller budget entirely. The key must be derived from the
     * LAST header line: ten requests, each with a DIFFERENT first line
     * but the same trusted edge append on the last line, exhaust ONE
     * caller budget — the eleventh rejects.
     */
    @Test
    void theCallerKeyIsTheLastForwardedHeaderLineNeverTheFirst() {
        PerCallerRateLimitGuard guard = guardWithDefaults();
        for (int attempt = 0; attempt < 10; attempt++) {
            DataFetchingEnvironment rotated = forwardedEnvironment(
                    "spoofed-" + attempt, "203.0.113.10", VICTIM_EMAIL);
            guard.acquirePermit(rotated, SURFACE);
        }
        // The eleventh request rotates its first line again but carries
        // the SAME edge append: under a first-line key it would open a
        // FRESH budget; under the last-line key the address budget is
        // exhausted and the request rejects.
        DataFetchingEnvironment eleventh = forwardedEnvironment(
                "spoofed-10", "203.0.113.10", VICTIM_EMAIL);
        assertThatThrownBy(() -> guard.acquirePermit(eleventh, SURFACE))
                .isInstanceOf(RequestNotPermitted.class);
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
