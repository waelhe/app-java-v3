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
 *
 * <p><b>The per-account law (the 2026-11-08 #806 round-9 review, Major
 * · Security &amp; Privacy):</b> {@link #perAccount()} is for ANONYMOUS
 * email-argument roots ONLY — a root with no verification to wait for,
 * where the bounded send itself is the defense. An AUTHENTICATED
 * surface must NOT declare it: consuming the account budget from the
 * UNVERIFIED email argument before the surface's own verification lets
 * an attacker with a few addresses burn the victim's account budget and
 * lock the victim's correct request out before any password check. The
 * authenticated surface's owning use case consumes the account budget
 * itself, AFTER its verification, through
 * {@link PerCallerRateLimitGuard#acquireAccountPermitForKey} keyed by
 * the authenticated principal (the password grant's contract).</p>
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
     *  budget). ANONYMOUS email-argument roots only — an authenticated
     *  surface consumes its account budget in the owning use case AFTER
     *  verification (see the class javadoc's per-account law; the
     *  password grant consumes through acquireAccountPermitForKey).
     *  Only meaningful on roots carrying an email argument. */
    boolean perAccount() default false;
}
