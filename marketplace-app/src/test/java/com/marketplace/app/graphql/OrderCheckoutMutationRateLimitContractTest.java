package com.marketplace.app.graphql;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 2026-11-06 #806 round (Minor · Stability &amp; Availability): the
 * order/checkout mutation families' rate-limit contract. The seven roots
 * previously rode NO limiter at all — the bulk-list bound capped only
 * each request's size, never the call rate
 * (graphql-target-architecture.md's own rule: "Rate-limit mutations by
 * subject and operation"). The families now carry the house's
 * two-layer authenticated-mutation shape — the per-caller address budget
 * FIRST ({@code PerCallerBudget} precedes the shared aspect, so a
 * rejected caller never burns the platform budget), then the named
 * per-JVM fail-fast ceiling ({@code graphqlOrderMutation} for the
 * order-command family behind the {@code requireAdmin} wall,
 * {@code graphqlCheckoutMutation} for the cart writes including the
 * admin's {@code checkoutDelete}), declared in the base profile (the
 * {@code RateLimiterDeclarationGuardTest} bijection) and pinned HERE on
 * the exact methods, so a future edit cannot silently drop either
 * layer of the family's only call-rate bound.
 */
class OrderCheckoutMutationRateLimitContractTest {

    private static <A extends Annotation> A annotationOf(Class<?> fetcher, String method, Class<A> type) {
        for (Method candidate : fetcher.getDeclaredMethods()) {
            if (candidate.getName().equals(method)) {
                return candidate.getAnnotation(type);
            }
        }
        throw new AssertionError("method not found: " + fetcher.getSimpleName() + "." + method);
    }

    private static void assertBudget(Class<?> fetcher, String method, String surface) {
        PerCallerBudget budget = annotationOf(fetcher, method, PerCallerBudget.class);
        assertThat(budget).as("%s.%s per-caller budget", fetcher.getSimpleName(), method).isNotNull();
        assertThat(budget.surface()).isEqualTo(surface);
    }

    private static void assertCeiling(Class<?> fetcher, String method, String limiter) {
        RateLimiter ceiling = annotationOf(fetcher, method, RateLimiter.class);
        assertThat(ceiling).as("%s.%s JVM ceiling", fetcher.getSimpleName(), method).isNotNull();
        assertThat(ceiling.name()).isEqualTo(limiter);
    }

    @Test
    void theOrderCommandFamilyRidesItsOwnTwoLayerWriteBudget() {
        // orderCancel/orderBulkCancel sit behind requireAdmin; their
        // per-family fail-fast ceiling is the documented
        // authenticated-mutation shape (the graphqlAccountMutation
        // precedent), and the per-caller address budget runs FIRST.
        assertBudget(OrderMutationDataFetcher.class, "orderCancel", "graphqlOrderMutation");
        assertCeiling(OrderMutationDataFetcher.class, "orderCancel", "graphqlOrderMutation");
        assertBudget(OrderMutationDataFetcher.class, "orderBulkCancel", "graphqlOrderMutation");
        assertCeiling(OrderMutationDataFetcher.class, "orderBulkCancel", "graphqlOrderMutation");
    }

    @Test
    void theCheckoutWriteFamilyRidesItsOwnTwoLayerWriteBudget() {
        // The customer's cart writes (linesAdd/Update/Delete/LineDelete)
        // and the admin's checkoutDelete share the checkout family's
        // budget — the surface sits behind the caller's authenticated
        // checkout ownership (the PreLoginGraphQlGate's anonymous surface
        // never carries it), so the family ceiling is the house's stated
        // bound for every leg.
        assertBudget(CheckoutDataFetcher.class, "checkoutLinesAdd", "graphqlCheckoutMutation");
        assertCeiling(CheckoutDataFetcher.class, "checkoutLinesAdd", "graphqlCheckoutMutation");
        assertBudget(CheckoutDataFetcher.class, "checkoutLinesUpdate", "graphqlCheckoutMutation");
        assertCeiling(CheckoutDataFetcher.class, "checkoutLinesUpdate", "graphqlCheckoutMutation");
        assertBudget(CheckoutDataFetcher.class, "checkoutLinesDelete", "graphqlCheckoutMutation");
        assertCeiling(CheckoutDataFetcher.class, "checkoutLinesDelete", "graphqlCheckoutMutation");
        assertBudget(CheckoutDataFetcher.class, "checkoutLineDelete", "graphqlCheckoutMutation");
        assertCeiling(CheckoutDataFetcher.class, "checkoutLineDelete", "graphqlCheckoutMutation");
        assertBudget(CheckoutDataFetcher.class, "checkoutDelete", "graphqlCheckoutMutation");
        assertCeiling(CheckoutDataFetcher.class, "checkoutDelete", "graphqlCheckoutMutation");
    }
}
