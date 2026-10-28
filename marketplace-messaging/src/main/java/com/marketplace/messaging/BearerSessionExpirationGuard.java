package com.marketplace.messaging;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Row 10 (agent findings 2026-10-25, CWE-613): the bearer-session
 * expiration guard — bearer-authenticated WebSocket sockets are CLOSED at
 * their token's expiry instead of carrying the retained principal
 * forever.
 *
 * <p><b>The hazard:</b> the documented token-based STOMP authentication
 * pattern authenticates the CONNECT frame once, and the framework then
 * "notes and saves the authenticated user and associate[s] it with
 * subsequent STOMP messages on the same session" (Spring Framework
 * Reference › WebSockets › Token Authentication — the pattern
 * {@code JwtChannelAuthenticationInterceptor} implements). Validation is
 * skipped after CONNECT, so when the 15-minute access token expires the
 * connection keeps its authority: sending messages, updating read state,
 * and receiving private conversation broadcasts under a principal the
 * resource server would no longer accept.</p>
 *
 * <p><b>The mechanism (the finding's recommendation, first half):</b>
 * every bearer session is closed at its token's {@code exp} —
 * {@code WebSocketSession.close} is the official termination, and the
 * framework's documented disconnect handling then removes the session's
 * subscriptions and user registry entries (the broker stops delivering,
 * {@code SessionDisconnectEvent} fires), forcing a fresh CONNECT with a
 * fresh token to resume. Both bearer paths are covered: a
 * handshake-authenticated session (the resource-server chain's
 * {@link JwtAuthenticationToken} is already the session principal at
 * establish time) is armed when the connection opens, and a
 * CONNECT-frame session (the browser path — no handshake principal) is
 * armed by {@code JwtChannelAuthenticationInterceptor} at the moment it
 * lifts the token.</p>
 *
 * <p><b>The second half</b> — rejecting expired authentication on
 * inbound frames — lives in the interceptor itself: a re-validation of
 * the retained token on every post-CONNECT frame, whose rejection path
 * the framework turns into a STOMP ERROR plus
 * {@code session.close(PROTOCOL_ERROR)} (bytecode-verified against
 * spring-websocket 7.0.10's {@code StompSubProtocolHandler#sendErrorMessage}).
 * This guard closes the IDLE socket (no inbound frame to reject) at the
 * same instant, so neither an active nor a quiet expired session keeps
 * its subscriptions alive.</p>
 *
 * <p><b>Why closing, not "just stop delivering":</b> leaving the socket
 * open-but-muted keeps the broker's subscription state and the user
 * registry entry alive under an expired principal, and lets the client
 * believe it is connected. A closed socket is the honest contract: the
 * client reconnects, presents a fresh token at CONNECT, and the
 * documented authentication boundary runs again.</p>
 *
 * <p><b>Lifecycle safety:</b> registrations and cancellations are
 * idempotent; a close on an already-closed session is a no-op; and the
 * {@link ObjectProvider} for {@link TaskScheduler} keeps module-slice
 * contexts (no scheduler bean) inert rather than broken — there the
 * inbound-frame safeguard remains the active defense.</p>
 */
final class BearerSessionExpirationGuard {

    /**
     * The close reason a client receives when its token expires:
     * 1008 (policy violation) — the WebSocket-close sibling of a 401,
     * with the cause spelled out for the reconnect logic.
     */
    static final CloseStatus TOKEN_EXPIRED = new CloseStatus(1008, "Access token expired");

    private final ObjectProvider<TaskScheduler> taskScheduler;

    /** Session id → the live WebSocketSession (for the expiry close). */
    private final ConcurrentMap<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();

    /** Session id → the scheduled expiry close (cancelled on early disconnect). */
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingCloses = new ConcurrentHashMap<>();

    BearerSessionExpirationGuard(ObjectProvider<TaskScheduler> taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /**
     * A connection opened (the tracking decorator's
     * {@code afterConnectionEstablished}). Handshake-bearer sessions —
     * those whose HTTP-layer principal is already the resource server's
     * token authentication — arm their expiry close here; the browser
     * path has no handshake principal and is armed later, at its
     * CONNECT-frame token lift.
     */
    void sessionOpened(WebSocketSession session) {
        if (session == null) {
            return;
        }
        sessionsById.put(session.getId(), session);
        if (session.getPrincipal() instanceof JwtAuthenticationToken token
                && token.getToken().getExpiresAt() != null) {
            scheduleClose(session.getId(), token.getToken().getExpiresAt());
        }
    }

    /**
     * A CONNECT-frame bearer token was lifted onto the session (the
     * browser path). Arms the expiry close for that session's id — the
     * STOMP session id is the WebSocket session id in Spring's STOMP
     * over WebSocket (the simpSessionId the accessor carries).
     *
     * <p>Review round (CodeRabbit on the 4a split, 2026-10-28): the
     * return value is the ADMISSION verdict. {@code false} means the
     * session was closed AT admission (an already-expired token — row 23's
     * immediate close); the interceptor must then abort the CONNECT
     * (return {@code null} from preSend) so no CONNECTED frame follows a
     * closed socket. Every other shape — scheduled close, inert
     * module-slice (no scheduler), absent arguments — is admission
     * ({@code true}): the socket stays open under its armed deadline.
     */
    boolean bearerTokenAuthenticated(String sessionId, Jwt jwt) {
        if (sessionId == null || jwt == null || jwt.getExpiresAt() == null) {
            return true;
        }
        return scheduleClose(sessionId, jwt.getExpiresAt());
    }

    /**
     * A connection closed (the tracking decorator's
     * {@code afterConnectionClosed}) — cancel the pending expiry close
     * and forget the session. The framework's own disconnect handling
     * has already removed its subscriptions.
     */
    void sessionClosed(WebSocketSession session) {
        if (session == null) {
            return;
        }
        sessionsById.remove(session.getId());
        cancelPendingClose(session.getId());
    }

    private boolean scheduleClose(String sessionId, Instant expiresAt) {
        if (expiresAt.isBefore(Instant.now())) {
            // Row 23 (agent findings 2026-10-26 14:33 export, CWE-613): an
            // already-past deadline on an ADMITTED session closes NOW. The
            // pre-fix shape returned without scheduling anything and leaned
            // on the interceptor's next inbound frame — but the JWT
            // validator accepts recently expired tokens within its clock
            // skew, so a session can be admitted holding a token whose
            // expiry has already passed; a QUIET session then carries the
            // retained principal and its private-message subscriptions
            // indefinitely (no inbound frame to reject, no scheduled
            // closure). The guard's own contract — "a closed socket is the
            // honest contract" — closes it immediately at admission time;
            // the clock-skew tolerance belongs to the validator's token
            // decision, never to an open-ended session lifetime.
            closeSession(sessionId);
            return false;
        }
        TaskScheduler scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            // Module-slice context: no scheduler bean. The inbound-frame
            // safeguard in the interceptor remains the active defense;
            // this guard stays inert rather than failing the context.
            return true;
        }
        cancelPendingClose(sessionId);
        ScheduledFuture<?> close = scheduler.schedule(() -> closeSession(sessionId), expiresAt);
        pendingCloses.put(sessionId, close);
        return true;
    }

    private void closeSession(String sessionId) {
        pendingCloses.remove(sessionId);
        WebSocketSession session = sessionsById.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(TOKEN_EXPIRED);
        }
        catch (IOException ignored) {
            // The socket is already dying — the container's own close
            // handling and the tracking decorator's afterConnectionClosed
            // complete the cleanup either way.
        }
    }

    private void cancelPendingClose(String sessionId) {
        ScheduledFuture<?> pending = pendingCloses.remove(sessionId);
        if (pending != null) {
            pending.cancel(false);
        }
    }
}
