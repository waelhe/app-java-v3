package com.marketplace.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * S4/N3 (comprehensive repair plan §10/2.1): the CSRF leg of the WebSocket
 * architecture — <b>the documented pass-through</b>, replacing the default
 * {@code XorCsrfChannelInterceptor} through the official bean-name extension
 * point ({@code getBeanOrNull("csrfChannelInterceptor", ChannelInterceptor.class)}
 * in {@code WebSocketMessageBrokerSecurityConfiguration#configureClientInboundChannel},
 * bytecode-verified against spring-security-config 7.1.1).
 *
 * <p><b>Why pass-through is the threat-model-exact semantics here — the full
 * measured chain:</b></p>
 * <ol>
 *   <li><b>The minted token proves nothing about the client.</b> Spring
 *       Security's own {@code CsrfTokenHandshakeInterceptor} (registered FIRST
 *       among the endpoint's handshake interceptors by the same configuration,
 *       bytecode-verified) reads the {@code DeferredCsrfToken} request
 *       attribute — which {@code CsrfFilter} sets unconditionally before its
 *       ignoring-matcher check — calls {@code .get()} (materializing a token
 *       AND an HttpSession), and puts a fresh {@code DefaultCsrfToken} into
 *       EVERY connection's WebSocket session attributes. The CI round-2 logs
 *       show four distinct minted tokens for four stateless connections: a
 *       session-attribute token is NOT a cookie-session marker — it is
 *       framework noise present for everyone, so a "token present → enforce"
 *       branch would reject every stateless client's CONNECT.</li>
 *   <li><b>No ambient CONNECT can exist in this architecture.</b> The /ws
 *       handshake lives in the STATELESS resource-server chain (the S4 root
 *       fix) — chain 2 never loads a session principal, so even a
 *       cookie-carrying browser arrives anonymous at the WebSocket layer.
 *       Every authentication this channel knows is EXPLICIT: the Bearer on
 *       the handshake (header-capable clients) or the Bearer on the CONNECT
 *       frame (browsers — the documented token-authentication pattern this
 *       PR implements). An explicit credential is CSRF-immune by
 *       construction: the attacker's page cannot know or attach the victim's
 *       token. The Same-Origin Policy defense for browsers remains where it
 *       belongs for WebSockets — the transport layer's
 *       {@code OriginHandshakeInterceptor} (the endpoint's
 *       {@code setAllowedOrigins}, enforced at every handshake).</li>
 *   <li><b>The authorization boundary is the message layer.</b> A CONNECT
 *       with a supplied token is authenticated by
 *       {@code JwtChannelAuthenticationInterceptor} (an invalid supplied
 *       token rejects the CONNECT — resource-server semantics); a tokenless
 *       CONNECT stays anonymous and is rejected by the
 *       {@code messageAuthorizationManager}'s
 *       {@code nullDestMatcher().authenticated()} — the integration guard
 *       proves both. Nothing anonymous reaches the broker.</li>
 * </ol>
 *
 * <p>This is the documented shape of "CSRF is not configurable when using
 * {@code @EnableWebSocketSecurity}" (Spring Security Reference › WebSocket
 * Security › Disable CSRF within WebSockets): the bean override is the
 * sanctioned way to neutralize the STOMP CSRF leg while keeping the
 * annotation's security-context and authorization machinery. The alternative
 * the reference shows — dropping {@code @EnableWebSocketSecurity} and wiring
 * the interceptors by hand — would re-implement what the annotation already
 * provides correctly.</p>
 */
@Configuration
class WebSocketCsrfConfiguration {

    /**
     * The bean NAME is the contract — the {@code @EnableWebSocketSecurity}
     * wiring looks this exact name up through the documented
     * {@code getBeanOrNull} extension point and replaces its default with it.
     * The pass-through is the empty interceptor: every
     * {@link ChannelInterceptor} default method already returns the message
     * unchanged — the bean's PRESENCE is the entire replacement (its absence
     * would hand every CONNECT to the framework's XOR enforcement).
     */
    @Bean
    ChannelInterceptor csrfChannelInterceptor() {
        return new ChannelInterceptor() {};
    }
}
