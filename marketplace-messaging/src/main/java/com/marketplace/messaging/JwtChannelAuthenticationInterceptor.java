package com.marketplace.messaging;

import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.Principal;

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
 *
 * <p><b>Row 10 (agent findings 2026-10-25, CWE-613) — the retained
 * principal must not outlive its token:</b> the documented pattern
 * authenticates CONNECT once and the framework associates that user with
 * every subsequent frame of the session — which means an expired 15-minute
 * token silently kept its authority on an established socket. Two
 * mechanisms close that hole:</p>
 * <ol>
 * <li><b>Every post-CONNECT inbound frame re-validates the retained
 * bearer token</b> through the SAME {@link JwtDecoder} the CONNECT did
 * (the finding's "reject expired authentication on inbound frames as an
 * additional safeguard"). A re-validation failure throws
 * {@link InvalidBearerTokenException}, and the framework turns an
 * interceptor exception on a client frame into a STOMP ERROR followed by
 * {@code session.close(PROTOCOL_ERROR)} — bytecode-verified against
 * spring-websocket 7.0.10's
 * {@code StompSubProtocolHandler#sendErrorMessage} — so the expired
 * frame is rejected AND the socket (with its subscriptions) is torn
 * down, never silently degraded.</li>
 * <li><b>The expiry itself closes the socket</b> even with no inbound
 * frame to reject (an idle-but-subscribed socket must stop receiving
 * private conversation broadcasts too): the lifted token's expiry arms
 * {@link BearerSessionExpirationGuard}, which closes the session at the
 * token's {@code exp}.</li>
 * </ol>
 * <p>Both apply to the handshake-bearer path as well: its retained
 * principal (resource server's {@link JwtAuthenticationToken}, possibly
 * translated by the identity interceptor into
 * {@code WebSocketUserIdentity}) is re-validated the same way, and the
 * guard arms from the session principal at connection establish. A
 * non-token principal (the cookie/session flow) is untouched by either
 * mechanism — session expiry governs that flow, not this one.</p>
 */
final class JwtChannelAuthenticationInterceptor implements ChannelInterceptor {

    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter;
    private final BearerSessionExpirationGuard expirationGuard;

    JwtChannelAuthenticationInterceptor(ObjectProvider<JwtDecoder> jwtDecoder,
                                        ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter,
                                        BearerSessionExpirationGuard expirationGuard) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.expirationGuard = expirationGuard;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Row 10: the retained principal must still carry a valid token
            // — re-validate on every subsequent inbound frame.
            return revalidateBearerAuthentication(accessor, message);
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
        // Module-slice contexts (messaging's own @ApplicationModuleTest) boot
        // without the shared security infrastructure — no decoder means no
        // token flow exists there at all; the CONNECT stays for the message
        // layer to decide (the documented inert state).
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        JwtAuthenticationConverter converter = jwtAuthenticationConverter.getIfAvailable();
        if (decoder == null || converter == null) {
            return message;
        }
        String token = authorization.substring("Bearer ".length());
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException ex) {
            // A SUPPLIED token that fails validation rejects the CONNECT — the
            // resource-server contract for a bad supplied token, never a
            // fall-through to anonymous.
            throw new InvalidBearerTokenException("Invalid WebSocket CONNECT token", ex);
        }
        Authentication authentication = converter.convert(jwt);
        JwtAuthenticationToken authenticationToken = authentication instanceof JwtAuthenticationToken jwtToken
                ? jwtToken
                : new JwtAuthenticationToken(jwt, authentication.getAuthorities());
        accessor.setUser(authenticationToken);
        // Row 10: arm the expiry close — this session's socket must not
        // outlive the token it authenticated with (the browser path: no
        // handshake principal armed the guard at connection establish).
        expirationGuard.bearerTokenAuthenticated(accessor.getSessionId(), jwt);
        return message;
    }

    /**
     * Row 10 (agent findings 2026-10-25, CWE-613): the additional
     * safeguard — a bearer-derived principal is re-validated on every
     * post-CONNECT inbound frame. The same {@link JwtDecoder} (with its
     * timestamp validators and clock skew) answers the exact question the
     * resource server would answer for the same token on REST: still
     * valid? An expired or otherwise now-invalid token throws
     * {@link InvalidBearerTokenException} — the frame is rejected and the
     * framework closes the socket with its subscriptions (see the class
     * javadoc for the verified ERROR + close path).
     *
     * <p>Deliberate no-ops (the same fail-closed pass-through family as
     * the CONNECT branch): an absent user is the authorization manager's
     * to judge; a non-token principal (the cookie/session flow, or a
     * module-slice testing principal) has no bearer token to re-validate;
     * and a context without the shared decoder infrastructure (the
     * module-slice shape) has no token flow at all.</p>
     */
    private Message<?> revalidateBearerAuthentication(StompHeaderAccessor accessor, Message<?> message) {
        Principal user = accessor.getUser();
        if (user == null) {
            return message;
        }
        Jwt retainedToken = bearerTokenOf(user);
        if (retainedToken == null) {
            return message;
        }
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder == null) {
            return message;
        }
        try {
            decoder.decode(retainedToken.getTokenValue());
        } catch (JwtException ex) {
            // The token the session was authenticated with is no longer
            // valid — most commonly expired. Rejecting the frame also closes
            // the socket: StompSubProtocolHandler's ERROR path (verified).
            // NOTE: the message MUST stay RFC 6750 ASCII (no em-dashes or
            // other non-ASCII punctuation): BearerTokenError silently
            // rejects a non-conforming description and InvalidBearerTokenException
            // then falls back to the generic "Invalid token" wording.
            throw new InvalidBearerTokenException(
                    "The session's access token is no longer valid - reconnect with a fresh token", ex);
        }
        return message;
    }

    /**
     * The bearer token behind a retained principal — either shape the
     * boundary produces: the lifted handshake token itself, or the
     * identity translator's {@code WebSocketUserIdentity} wrapping it.
     * Non-token principals answer null (not this flow).
     */
    private static Jwt bearerTokenOf(Principal user) {
        Authentication authentication;
        if (user instanceof WebSocketUserIdentity translated) {
            authentication = translated.delegate();
        }
        else if (user instanceof Authentication auth) {
            authentication = auth;
        }
        else {
            return null;
        }
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        return null;
    }
}
