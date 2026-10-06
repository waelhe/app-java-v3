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
 * <p>Bodies stay minimal and PII-free by construction: the success leg is a
 * debug-level marker, the failure leg logs the event type and the exception's
 * class and message (the framework's failure messages — "Bad credentials",
 * "User account is locked" — carry no subject identifiers; a locked-out or
 * unknown account is observable without its name). The listener "can be used
 * independently from the servlet API", as the reference notes — one grep over
 * the log now answers "when did authentication start failing and with what
 * error", the exact operational visibility the gap described.</p>
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
        log.debug("Authentication success event received (source: {})", success.getSource());
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
        Exception exception = failures.getException();
        log.warn("Authentication failure event received: {} — {}",
                exception.getClass().getSimpleName(),
                exception.getMessage());
    }
}
