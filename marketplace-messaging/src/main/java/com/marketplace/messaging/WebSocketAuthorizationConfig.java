package com.marketplace.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.expression.MessageExpressionAuthorizationManager;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * The WebSocket message authorization rules (the Spring Security 7.1.1
 * compliance wave, §7-а) — the documented {@code AuthorizationManager}
 * bean, published for the manual wiring in {@link WebSocketSecurityConfig}
 * exactly as the reference prescribes it ("simply … publish an
 * {@code AuthorizationManager<Message<?>>} bean").
 *
 * <p><b>The builder comes from the public API now:</b> under
 * {@code @EnableWebSocketSecurity} the {@code Builder} method parameter of
 * the bean was resolved from a prototype bean the annotation's imported
 * configuration registers ({@code MessageMatcherAuthorizationManagerConfiguration},
 * verified against spring-security-config 7.1.1). Without the annotation
 * that prototype bean does not exist, and the documented manual path builds
 * the manager through {@link MessageMatcherDelegatingAuthorizationManager#builder()}
 * — the public static factory that initializes
 * {@code PathPatternMessageMatcher.withDefaults()}, the same default the
 * annotation's prototype bean carried (source-verified).</p>
 *
 * <p>The rules themselves are unchanged from the previous wiring — the
 * authorization boundary of this module's message layer:</p>
 * <ul>
 *   <li>a message without a destination (CONNECT/DISCONNECT/HEARTBEAT)
 *       requires authentication — the tokenless-CONNECT guard the
 *       integration tests pin;</li>
 *   <li>{@code /app/**} sends require authentication;</li>
 *   <li>{@code /topic/notifications/{userId}} subscribes require the
 *       subscriber to BE the addressed user (the reference's documented
 *       {@code MessageExpressionAuthorizationManager} migration shape for
 *       SpEL matchers);</li>
 *   <li>{@code /topic/conversations/{conversationId}} subscribes are judged
 *       by the conversation-participant guard;</li>
 *   <li>every other {@code /topic/**} subscribe is denied, and
 *       {@code anyMessage().denyAll()} closes the matrix — the reference's
 *       own "this is a good idea to ensure that you do not miss any
 *       messages".</li>
 * </ul>
 */
@Configuration
public class WebSocketAuthorizationConfig {

    private final ConversationSubscriptionAuthorizationManager conversationSubscriptionAuthorizationManager;

    public WebSocketAuthorizationConfig(
            ConversationSubscriptionAuthorizationManager conversationSubscriptionAuthorizationManager) {
        this.conversationSubscriptionAuthorizationManager = conversationSubscriptionAuthorizationManager;
    }

    /**
     * The message authorization manager — the documented bean the manual
     * wiring consumes.
     *
     * @return the delegating authorization manager with this module's rules
     */
    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager() {
        MessageMatcherDelegatingAuthorizationManager.Builder messages =
                MessageMatcherDelegatingAuthorizationManager.builder();
        messages
                .nullDestMatcher().authenticated()
                .simpDestMatchers("/app/**").authenticated()
                .simpSubscribeDestMatchers("/topic/notifications/{userId}")
                    .access(new MessageExpressionAuthorizationManager("#userId == authentication.name"))
                .simpSubscribeDestMatchers("/topic/conversations/{conversationId}")
                    .access(conversationSubscriptionAuthorizationManager)
                .simpSubscribeDestMatchers("/topic/**").denyAll()
                .anyMessage().denyAll();
        return messages.build();
    }
}
