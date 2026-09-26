package com.marketplace.messaging;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * S4/N3 root fix (comprehensive repair plan §10/2.1): the documented
 * token-based authentication pattern for STOMP over WebSocket — Spring
 * Framework Reference › WebSockets › STOMP › Token Authentication
 * ("Doing so requires two simple steps: 1. Use the STOMP client to pass
 * authentication headers at connect time. 2. Process the authentication
 * headers with a ChannelInterceptor... An interceptor needs only to
 * authenticate and set the user header on the CONNECT Message. Spring
 * notes and saves the authenticated user and associate it with subsequent
 * STOMP messages on the same session." — verbatim, saved at
 * {@code scripts/doc-verify/ws/framework-stomp-token-based.html}).
 *
 * <p><b>Why this interceptor exists:</b> browser clients cannot set HTTP
 * headers on the WebSocket handshake ("browser clients can use only
 * standard authentication headers... and cannot (for example) provide
 * custom headers" — the same documented page), so the resource-server
 * chain authenticating the handshake (the {@code /ws/**} line added to
 * chain 2's {@code securityMatcher}) serves only header-capable clients
 * (the Java/Node STOMP clients, the edge BFF). Browsers carry the access
 * token on the CONNECT frame's {@code Authorization} STOMP header — this
 * interceptor lifts it into the session's user exactly the documented
 * way, through the SAME {@link JwtDecoder} and the SAME
 * {@link JwtAuthenticationConverter} the resource-server chain uses, so
 * the STOMP authentication is identical to the REST one (subject,
 * authorities from the {@code roles} claim).
 *
 * <p><b>Deliberate no-ops:</b> a CONNECT that already carries a user (the
 * handshake was authenticated — the framework hands the HTTP Principal
 * off to the WebSocket session: "WebSockets reuse the same authentication
 * information that is found in the HTTP request") is left untouched —
 * the HTTP-layer authentication wins, never re-authenticated. A CONNECT
 * without an {@code Authorization} header is also left untouched — the
 * message layer's own authorization manager
 * ({@code nullDestMatcher().authenticated()}) rejects it; the session
 * flow (chain 3 cookie clients) keeps its session Principal. Only a
 * <em>supplied</em> token is validated — an invalid one rejects the
 * CONNECT with {@link InvalidBearerTokenException} (the resource-server
 * semantics for a supplied-bad token), never a silent fall-through.
 */
final class JwtChannelAuthenticationInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    JwtChannelAuthenticationInterceptor(JwtDecoder jwtDecoder,
                                        JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        if (accessor.getUser() != null) {
            // The HTTP-layer authentication (handshake Bearer header, or the
            // session Principal) already owns this session — never override it.
            return message;
        }
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            // No supplied token — not this interceptor's flow. The message
            // layer's authorization manager decides the CONNECT's fate.
            return message;
        }
        String token = authorization.substring("Bearer ".length());
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException ex) {
            // A SUPPLIED token that fails validation rejects the CONNECT — the
            // resource-server contract for a bad supplied token, never a
            // fall-through to anonymous.
            throw new InvalidBearerTokenException("Invalid WebSocket CONNECT token", ex);
        }
        Authentication authentication = jwtAuthenticationConverter.convert(jwt);
        JwtAuthenticationToken authenticationToken = authentication instanceof JwtAuthenticationToken jwtToken
                ? jwtToken
                : new JwtAuthenticationToken(jwt, authentication.getAuthorities());
        accessor.setUser(authenticationToken);
        return message;
    }
}
