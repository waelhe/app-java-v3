package com.marketplace.app.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The advice behind {@link PerCallerBudget} — consumes the caller's own
 * budget (and, when requested, the account's) BEFORE every other advice
 * on the method, most importantly BEFORE resilience4j's
 * {@code @RateLimiter} advice acquires the shared platform permit.
 *
 * <p><b>Ordering (the wave-4 review's Major finding):</b> Spring AOP
 * composes advices by aspect order — the LOWER order runs on the OUTSIDE.
 * resilience4j's {@code RateLimiterAspect} defaults to order 405
 * ({@code resilience4j.rateLimiter.order}); this aspect declares
 * {@link #ORDER 100}, so a request the per-caller (or per-account) budget
 * rejects throws before the shared limiter is ever consulted — rejected
 * attempts can no longer starve the shared budget for other callers
 * (the exact starvation the review demonstrated for
 * {@code graphqlTokenMutation} and {@code graphqlAccountAnonymousMutation}).</p>
 *
 * <p><b>The environment:</b> the caller key derives from the request, so
 * the advice resolves the root method's {@link DataFetchingEnvironment}
 * argument. A root without one is left untouched (nothing to key on) —
 * the same fail-safe direction the guard itself takes.</p>
 */
@Aspect
@Component
@Order(PerCallerBudgetAspect.ORDER)
public class PerCallerBudgetAspect {

    /**
     * Precedes resilience4j's RateLimiterAspect (default order 405) — the
     * per-caller rejection must happen before the shared permit burn.
     */
    public static final int ORDER = 100;

    private final PerCallerRateLimitGuard guard;

    public PerCallerBudgetAspect(PerCallerRateLimitGuard guard) {
        this.guard = guard;
    }

    @Around("@annotation(perCallerBudget)")
    public Object enforce(ProceedingJoinPoint pjp, PerCallerBudget perCallerBudget) throws Throwable {
        DataFetchingEnvironment environment = firstEnvironment(pjp.getArgs());
        if (environment != null) {
            guard.acquirePermit(environment, perCallerBudget.surface());
            if (perCallerBudget.perAccount()) {
                guard.acquireAccountPermit(environment, perCallerBudget.surface());
            }
        }
        return pjp.proceed();
    }

    private static DataFetchingEnvironment firstEnvironment(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof DataFetchingEnvironment environment) {
                return environment;
            }
        }
        return null;
    }
}
