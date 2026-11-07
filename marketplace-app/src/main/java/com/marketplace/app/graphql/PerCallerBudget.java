package com.marketplace.app.graphql;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The per-CALLER budget for an anonymous-reachable GraphQL root — the
 * declarative form of {@link PerCallerRateLimitGuard#acquirePermit}.
 *
 * <p><b>Why an annotation (the wave-4 review's Major ordering finding on
 * {@code tokenCreate}):</b> the shared {@code @RateLimiter} advice
 * (resilience4j's {@code RateLimiterAspect}) acquires the SHARED permit
 * around the whole method — a body-level guard call inside it runs AFTER
 * that acquisition, so one anonymous caller could burn the shared
 * platform budget with requests its OWN per-caller budget was already
 * rejecting. The fix is aspect ORDERING: this annotation's advice
 * declares {@link PerCallerBudgetAspect#ORDER an order} that precedes
 * resilience4j's ({@code resilience4j.rateLimiter.order}, default 405),
 * so the caller's own permit is consumed FIRST and a rejected request
 * never reaches the shared limiter at all.</p>
 *
 * <p><b>Scope:</b> roots whose caller budget is address-keyed by default;
 * {@link #perAccount()} additionally consumes a second permit keyed by
 * the SHA-256 of the normalized email argument (the credential-stuffing
 * defense — one attacker distributing attempts across many addresses
 * still has one budget per victim account).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PerCallerBudget {

    /** The per-caller surface name — the limiter config entry rides the
     *  shared registry's {@code perCallerGrant} config (the yml), and the
     *  instance key is {@code <surface>-caller:<address>}. */
    String surface();

    /** Additionally consume a permit keyed by the hash of the request's
     *  normalized email argument (the account-level credential-stuffing
     *  budget). Only meaningful on roots carrying an email argument. */
    boolean perAccount() default false;
}
