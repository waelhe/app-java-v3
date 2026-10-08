package com.marketplace.messaging;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * S4/N3 (comprehensive repair plan §10/2.1): the broker wiring for the token
 * era. <b>The class-level {@link Order} is load-bearing (CI round 2's measured
 * root cause):</b> {@code DelegatingWebSocketMessageBrokerConfiguration} takes
 * its configurers as an {@code @Autowired List} — sorted by
 * {@code AnnotationAwareOrderComparator} — and invokes each one's
 * {@code configureClientInboundChannel} on the SAME registration, so the
 * interceptors run in configurer order. This class and the security wiring
 * ({@code WebSocketSecurityConfig} — the documented manual configuration of
 * the 7.1.1 compliance wave §7-а) are competitors for that list, and an
 * unordered security configurer can land anywhere: the measured CI round
 * once produced [SecurityContext, Authorization, JWT] where the lifted
 * CONNECT user arrived AFTER the authorization decision, every token CONNECT
 * read as anonymous, and the authorization interceptor answered
 * {@code AccessDeniedException} (the exact CI signature: the connection dies
 * at CONNECT, and the synthetic DISCONNECT then fails the same way —
 * measured in the round-2 logs).
 * {@code HIGHEST_PRECEDENCE} puts the JWT lifter FIRST:
 * [JWT, Identity, SecurityContext, Authorization] — the user is lifted, the
 * security context is populated from it, and the authorization manager sees
 * the authenticated CONNECT it is meant to judge. (Bytecode-verified against
 * spring-websocket 7.0.9 + spring-security-config 7.1.1.)
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final MarketplaceProperties properties;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter;
    private final ObjectProvider<UserLookupPort> userLookupPort;

    public WebSocketConfig(MarketplaceProperties properties,
                           ObjectProvider<JwtDecoder> jwtDecoder,
                           ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter,
                           ObjectProvider<UserLookupPort> userLookupPort) {
        this.properties = properties;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.userLookupPort = userLookupPort;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
    }

    /**
     * S4/N3 root fix (comprehensive repair plan §10/2.1): the documented
     * registration point for token-based STOMP authentication — "Process
     * the authentication headers with a ChannelInterceptor" (Spring
     * Framework Reference › STOMP › Token Authentication, verbatim, saved
     * at {@code scripts/doc-verify/ws/framework-stomp-token-based.html}).
     * The interceptor only lifts a supplied CONNECT-frame bearer token;
     * handshake-level and session authentications pass it untouched, and
     * the message authorization manager stays the authorization boundary.
     * It must register BEFORE the security interceptors — see the class
     * javadoc (the {@link Order} root cause of CI round 2).
     *
     * <p><b>ObjectProvider (the Modulith module-slice shape — CI round 1
     * root):</b> the decoder/converter beans live in the shared security
     * infrastructure; a module-slice context (messaging's own
     * {@code @ApplicationModuleTest}) boots WITHOUT them, so constructor
     * injection fails the whole context. The providers resolve lazily at
     * CONNECT time: in the full application they are always present (the
     * same beans the resource-server chain uses); in a module slice the
     * interceptor is inert — no real tokens exist there to lift.
     *
     * <p><b>R7 (comprehensive-review-ar fix plan §4, Wave 5):</b> the
     * identity translator registers RIGHT AFTER the JWT lifter — the
     * CONNECT's lifted (or handshake-authenticated) token principal gets
     * its name translated from the token subject to the stable user id
     * through the boundary's first-party {@link WebSocketUserIdentity}
     * (the same authorities and Jwt principal, the UUID as the name — see
     * that type's javadoc for the measured framework facts behind the
     * choice), so the controller, both subscription guards and the
     * message authorization manager all speak the UUID identity after
     * this boundary (see
     * {@link WebSocketIdentityChannelInterceptor}). The ordering inside
     * the registration is load-bearing: the translator must see the user
     * the lifter set on the same CONNECT message —
     * {@code interceptors(a, b)} runs {@code a} before {@code b} — and
     * both must precede the security configurers' interceptors (the
     * class-level {@link Order}).
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new JwtChannelAuthenticationInterceptor(jwtDecoder, jwtAuthenticationConverter),
                new WebSocketIdentityChannelInterceptor(userLookupPort));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(128 * 1024);
        registry.setSendBufferSizeLimit(512 * 1024);
        registry.setSendTimeLimit(15 * 1000);
        registry.setTimeToFirstMessage(30000);
    }
}
