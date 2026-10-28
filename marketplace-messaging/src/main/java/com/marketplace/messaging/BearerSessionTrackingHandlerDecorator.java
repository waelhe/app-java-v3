package com.marketplace.messaging;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Row 10 (agent findings 2026-10-25, CWE-613): the session-tracking leg
 * of the bearer-session expiration guard — the official
 * {@link org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory}
 * seam ({@code WebSocketTransportRegistration.addDecoratorFactory}), so
 * every established WebSocket session is handed to
 * {@link BearerSessionExpirationGuard} exactly once and every closed one
 * is released with it.
 *
 * <p>The decorator only OBSERVES the lifecycle — every callback delegates
 * unchanged to the wrapped handler (the official decorator contract), so
 * the broker machinery below it is untouched. Without this seam the guard
 * would have no {@link WebSocketSession} to close: the STOMP lifecycle
 * events ({@code SessionConnectedEvent} and friends) carry the message
 * and principal but not the session object (bytecode-verified against
 * spring-websocket 7.0.10's {@code StompSubProtocolHandler}).</p>
 */
final class BearerSessionTrackingHandlerDecorator extends WebSocketHandlerDecorator {

    private final BearerSessionExpirationGuard expirationGuard;

    BearerSessionTrackingHandlerDecorator(WebSocketHandler delegate,
                                          BearerSessionExpirationGuard expirationGuard) {
        super(delegate);
        this.expirationGuard = expirationGuard;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        expirationGuard.sessionOpened(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        expirationGuard.sessionClosed(session);
        super.afterConnectionClosed(session, closeStatus);
    }
}
