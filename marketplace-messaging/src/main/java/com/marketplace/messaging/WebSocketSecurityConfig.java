package com.marketplace.messaging;

import java.util.List;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The WebSocket security wiring (the Spring Security 7.1.1 compliance wave,
 * §7-а) — <b>the documented manual configuration, verbatim</b>, from the
 * reference's own "Disable CSRF within WebSockets" listing
 * (servlet/integrations/websocket.html):
 *
 * <blockquote>"At this point, CSRF is not configurable when using
 * {@code @EnableWebSocketSecurity}, though this will likely be added in a
 * future release. To disable CSRF, instead of using
 * {@code @EnableWebSocketSecurity}, you can use XML support or <b>add the
 * Spring Security components yourself</b>"</blockquote> — followed by exactly
 * this class's shape: a {@link WebSocketMessageBrokerConfigurer} whose
 * {@link #configureClientInboundChannel(ChannelRegistration)} registers
 * {@code new SecurityContextChannelInterceptor()} and an
 * {@code AuthorizationChannelInterceptor} built from the
 * {@link AuthorizationManager} bean, with the reference's
 * {@code AuthenticationPrincipalArgumentResolver} argument resolver and the
 * {@code SpringAuthorizationEventPublisher} event publisher.
 *
 * <p><b>Why the documented manual path is the one taken (the deviation this
 * replaces):</b> the previous wiring kept {@code @EnableWebSocketSecurity}
 * and neutralized its CSRF leg through a bean named
 * {@code csrfChannelInterceptor} — an internal extension point the
 * reference never documents (verified against spring-security-config 7.1.1:
 * {@code WebSocketMessageBrokerSecurityConfiguration} looks the name up via
 * {@code getBeanOrNull}, and the reference page says CSRF is "not
 * configurable" under the annotation, offering this manual wiring as the
 * only documented alternative). That bean-name override was an undocumented
 * intervention into the annotation's machinery; it is deleted. This class
 * carries no CSRF interceptor at all — the no-CSRF state is the documented
 * posture of this wiring, not an override of something enabled.</p>
 *
 * <p><b>What the annotation provided and this wiring re-provides on the
 * documented seams:</b> the {@code SecurityContextChannelInterceptor}
 * (SecurityContext population from the {@code simpUser} header), the
 * {@code AuthorizationChannelInterceptor} (the message-layer authorization
 * boundary — the same {@link AuthorizationManager} bean, the same rules,
 * now defined in {@link WebSocketAuthorizationConfig}), the
 * {@code AuthenticationPrincipalArgumentResolver}, and the authorization
 * events through {@code SpringAuthorizationEventPublisher}. What the
 * annotation additionally registered — the {@code XorCsrfChannelInterceptor}
 * CSRF enforcement and the {@code CsrfTokenHandshakeInterceptor} — is
 * exactly what the reference's manual wiring leaves out, and the websocket
 * authorization-observation post-processing that came with the annotation's
 * {@code WebSocketObservationImportSelector} is likewise not part of the
 * documented manual listing.</p>
 *
 * <p><b>The interceptor order is load-bearing and unchanged:</b>
 * {@link WebSocketConfig} (class-level {@code @Order(HIGHEST_PRECEDENCE)})
 * registers the JWT lifter and the identity translator first, and this
 * unordered configurer follows — the inbound chain reads
 * [JWT, Identity, SecurityContext, Authorization]: the lifted CONNECT user
 * is in place before the security context is populated from it and before
 * the authorization manager judges the CONNECT. See {@link WebSocketConfig}'s
 * javadoc for the measured history of that order.</p>
 */
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final ApplicationContext applicationContext;

    private final AuthorizationManager<Message<?>> authorizationManager;

    public WebSocketSecurityConfig(ApplicationContext applicationContext,
                                   AuthorizationManager<Message<?>> authorizationManager) {
        this.applicationContext = applicationContext;
        this.authorizationManager = authorizationManager;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(new AuthenticationPrincipalArgumentResolver());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        AuthorizationChannelInterceptor authz = new AuthorizationChannelInterceptor(authorizationManager);
        AuthorizationEventPublisher publisher = new SpringAuthorizationEventPublisher(applicationContext);
        authz.setAuthorizationEventPublisher(publisher);
        registration.interceptors(new SecurityContextChannelInterceptor(), authz);
    }
}
