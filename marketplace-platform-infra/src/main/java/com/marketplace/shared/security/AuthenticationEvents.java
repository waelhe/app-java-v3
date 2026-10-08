package com.marketplace.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The documented authentication-events listener (the Spring Security 7.1.1
 * compliance wave, G-1) — the reference's own sample shape, verbatim
 * (Authentication Events, servlet/authentication/events.html):
 *
 * <pre>{@code
 * @Component
 * public class AuthenticationEvents {
 *     @EventListener
 *     public void onSuccess(AuthenticationSuccessEvent success) {
 *         // ...
 *     }
 *
 *     @EventListener
 *     public void onFailure(AbstractAuthenticationFailureEvent failures) {
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>The publisher half of the documented recipe — "To listen for these
 * events, you must first publish an AuthenticationEventPublisher. Spring
 * Security's DefaultAuthenticationEventPublisher works fine for this
 * purpose" — is ALREADY automatic management here: Spring Boot 4.1.1's
 * {@code SecurityAutoConfiguration} declares
 * {@code @ConditionalOnMissingBean DefaultAuthenticationEventPublisher} (the
 * automatic path, verified against the spring-boot-autoconfigure 4.1.1
 * sources), so the events for every successful or failed authentication
 * (form login on the authorization server's login page, the client
 * credentials and authorization-code grants, bearer validation failures
 * surfaced as {@code InvalidBearerTokenException}) reach the Spring event
 * bus without a single hand-wired bean.</p>
 *
 * <p>Bodies stay minimal and PII-free by construction: the success leg is
 * a debug-level marker carrying only the event's own type, and the failure
 * leg logs the event type and the exception's class — never the event's
 * source (the {@link Authentication} object: principal and details) and
 * never the exception's message (a wrapped provider exception's message
 * can carry subject identifiers, so it is dropped per the measured
 * community rule "never log raw tokens, credentials, or other sensitive
 * values, even at debug level"). The failure EVENT TYPE is itself the
 * documented exception-to-event table's category (bad credentials,
 * locked, expired, ...), so one grep over the log still answers "when did
 * authentication start failing and why", the exact operational visibility
 * the gap described.</p>
 */
@Component
public class AuthenticationEvents {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationEvents.class);

    /**
     * The documented success listener — fires for every successful
     * {@link Authentication} (Spring Security publishes an
     * {@code AuthenticationSuccessEvent} "for each authentication that
     * succeeds").
     *
     * @param success the published success event
     */
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent success) {
        log.debug("Authentication success event received ({})",
                success.getClass().getSimpleName());
    }

    /**
     * The documented failure listener — fires for every failed
     * {@link Authentication} mapped by
     * {@code DefaultAuthenticationEventPublisher} (bad credentials, disabled,
     * locked, expired, credentials expired, provider not found, service
     * failure — the reference's documented exception-to-event table).
     *
     * @param failures the published failure event
     */
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent failures) {
        log.warn("Authentication failure event received: {} — {}",
                failures.getClass().getSimpleName(),
                failures.getException().getClass().getSimpleName());
    }
}
