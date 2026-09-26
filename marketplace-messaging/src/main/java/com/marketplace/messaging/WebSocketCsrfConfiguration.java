package com.marketplace.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.Map;

/**
 * S4/N3 root fix (comprehensive repair plan §10/2.1): the CSRF leg of the
 * WebSocket authentication architecture. The {@code @EnableWebSocketSecurity}
 * wiring registers {@link XorCsrfChannelInterceptor} by default — measured
 * against the 7.1.1 bytecode, it reads the {@link CsrfToken} from the
 * WebSocket session attributes and throws {@code MissingCsrfTokenException}
 * when absent, which would reject the CONNECT of EVERY stateless token
 * client (no {@code HttpSession} means no session attributes means no
 * token). The same bytecode shows the official extension point: the
 * configuration looks up a bean NAMED {@code csrfChannelInterceptor}
 * ({@code getBeanOrNull("csrfChannelInterceptor", ChannelInterceptor.class)}
 * in {@code WebSocketMessageBrokerSecurityConfiguration#configureClientInboundChannel})
 * and uses it instead of the default when present.
 *
 * <p><b>The semantics (same-origin protection kept where it applies):</b>
 * <ul>
 *   <li>A session {@code CsrfToken} IS present (the cookie-session flow —
 *       the handshake carried an {@code HttpSession} with the token the
 *       login chain stored) — the CONNECT is enforced by the framework's
 *       own {@link XorCsrfChannelInterceptor}, byte-identical semantics:
 *       the STOMP header must carry the matching token or the CONNECT is
 *       rejected. Cross-origin browser attacks against session clients
 *       keep the full same-origin defense.</li>
 *   <li>No session token (the stateless token flow this fix opens) — the
 *       CONNECT passes the CSRF leg: the JWT on the CONNECT frame is an
 *       EXPLICIT credential, not ambient cookie authority, so the
 *       cross-site-request-forgery threat model does not apply (the
 *       attacker's page cannot know or attach the victim's token). The
 *       CONNECT is still authenticated by
 *       {@link JwtChannelAuthenticationInterceptor} and authorized by the
 *       {@code messageAuthorizationManager} ({@code nullDestMatcher()
 *       .authenticated()} + {@code denyAll()} defaults) — nothing is
 *       anonymous here.</li>
 * </ul>
 */
@Configuration
class WebSocketCsrfConfiguration {

    /**
     * The bean NAME is the contract — the {@code @EnableWebSocketSecurity}
     * wiring looks this exact name up through the documented
     * {@code getBeanOrNull} extension point and replaces its default with it.
     */
    @Bean
    ChannelInterceptor csrfChannelInterceptor() {
        XorCsrfChannelInterceptor frameworkDefault = new XorCsrfChannelInterceptor();
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                Map<String, Object> sessionAttributes =
                        SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
                boolean sessionCsrfTokenPresent = sessionAttributes != null
                        && sessionAttributes.get(CsrfToken.class.getName()) != null;
                if (!sessionCsrfTokenPresent) {
                    // The stateless token flow — no session, no ambient
                    // credential, CSRF-immune by construction. The JWT
                    // interceptor and the message authorization manager own
                    // this CONNECT.
                    return message;
                }
                // The cookie-session flow — the framework's own enforcement,
                // unchanged (XOR token comparison against the STOMP header).
                return frameworkDefault.preSend(message, channel);
            }
        };
    }
}
