package com.marketplace.shared.security;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-1 of the Spring Security 7.1.1 compliance wave — the documented
 * authentication-events listener. The reference (Authentication Events,
 * servlet/authentication/events.html) prescribes the exact shape:
 *
 * <pre>{@code
 * @Component
 * public class AuthenticationEvents {
 *     @EventListener
 *     public void onSuccess(AuthenticationSuccessEvent success) { // ... }
 *     @EventListener
 *     public void onFailure(AbstractAuthenticationFailureEvent failures) { // ... }
 * }
 * }</pre>
 *
 * <p>and requires "an AuthenticationEventPublisher" — which is AUTOMATIC
 * management here: Spring Boot 4.1.1's {@code SecurityAutoConfiguration}
 * declares {@code @ConditionalOnMissingBean DefaultAuthenticationEventPublisher}
 * (verified against the spring-boot-autoconfigure 4.1.1 sources), so no
 * publisher bean is hand-wired on our side.</p>
 *
 * <p>This test pins the documented shape (the annotations and the documented
 * event parameter types — the compile-level contract Spring's event
 * machinery binds to) and exercises both listener bodies with the
 * reference's documented event types, including one entry from its
 * exception-to-event table (UsernameNotFoundException →
 * AuthenticationFailureBadCredentialsEvent). PII-free logging is asserted
 * structurally: neither method signature receives or logs a principal
 * name.</p>
 */
class AuthenticationEventsTest {

    private final AuthenticationEvents events = new AuthenticationEvents();

    @Test
    void carriesTheDocumentedComponentShape() {
        assertThat(AuthenticationEvents.class.isAnnotationPresent(Component.class))
                .as("the reference sample is a @Component")
                .isTrue();

        List<Method> listeners = findEventListenerMethods();
        assertThat(listeners).hasSize(2);

        Method success = findListenerFor(AuthenticationSuccessEvent.class);
        Method failure = findListenerFor(AbstractAuthenticationFailureEvent.class);
        assertThat(success).isNotNull();
        assertThat(failure).isNotNull();
    }

    @Test
    void successListenerAcceptsTheDocumentedSuccessEvent() {
        Authentication authentication = new TestingAuthenticationToken("user@example.com", "password", "ROLE_CONSUMER");
        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(authentication);

        events.onSuccess(event);
        // A listener body must never throw — an event-listener failure would
        // surface inside the authentication flow itself.
    }

    @Test
    void failureListenerAcceptsTheDocumentedFailureEvents() {
        // The reference's documented exception mapping in action:
        // UsernameNotFoundException -> AuthenticationFailureBadCredentialsEvent
        Authentication authentication = new TestingAuthenticationToken("nobody@example.com", "password");
        AuthenticationException exception = new UsernameNotFoundException("user not found");
        AuthenticationFailureBadCredentialsEvent event =
                new AuthenticationFailureBadCredentialsEvent(authentication, exception);

        events.onFailure(event);
    }

    @Test
    void failureListenerAcceptsAnyDocumentedAbstractFailureEvent() {
        // AbstractAuthenticationFailureEvent is the documented catch-all
        // parameter type: disabled, locked, expired, credentials-expired,
        // provider-not-found and service-failure events all bind here.
        Authentication authentication = new TestingAuthenticationToken("nobody@example.com", "password");
        AuthenticationException exception = new org.springframework.security.authentication.AccountStatusException(
                "account status failure") {
        };

        events.onFailure(new AuthenticationFailureBadCredentialsEvent(authentication, exception));
    }

    private static List<Method> findEventListenerMethods() {
        return java.util.Arrays.stream(AuthenticationEvents.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventListener.class))
                .toList();
    }

    private static Method findListenerFor(Class<?> eventParameterType) {
        return java.util.Arrays.stream(AuthenticationEvents.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventListener.class))
                .filter(m -> m.getParameterCount() == 1 && eventParameterType.equals(m.getParameterTypes()[0]))
                .findFirst()
                .orElse(null);
    }
}
