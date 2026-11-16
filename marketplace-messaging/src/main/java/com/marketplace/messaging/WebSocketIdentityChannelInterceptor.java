package com.marketplace.messaging;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/**
 * R7 (comprehensive-review-ar fix plan §4, Wave 5 — the unified WebSocket
 * identity): the ONE translation level at the WebSocket boundary. The
 * minted access tokens carry the login handle (the email for the standard
 * flow) as the {@code sub} claim, and the shared
 * {@code JwtAuthenticationConverter} uses the default principal claim —
 * so the STOMP principal's name is the subject, while every downstream
 * consumer of the identity on this boundary speaks the stable user id
 * (UUID): the notifications topic guard
 * ({@code /topic/notifications/{userId}} matches
 * {@code authentication.name}), the conversation subscription guard
 * ({@code UUID.fromString(auth.getName())}) and the STOMP controller
 * ({@code UUID.fromString(principal.getName())}). Before this interceptor
 * the three could never match a real token's principal — the review's R7
 * finding, measured: a JWT client's subscription to its own UUID topic
 * was always denied.
 *
 * <p><b>The translation:</b> the token principal is re-worn as
 * {@link WebSocketUserIdentity} — the SAME authorities, the SAME
 * credentials, the SAME Jwt as the principal, the stable user id as the
 * name (see that type's javadoc for why a first-party boundary type and
 * not the official name-carrying {@code JwtAuthenticationToken}
 * constructor: the re-minted token is value-equal to the lifted one and
 * {@code MessageHeaderAccessor.setHeader} skips value-equal writes —
 * bytecode-verified against spring-messaging 7.0.9 + spring-security-core
 * 7.1.1, the measured no-op). The subject-to-id resolution rides the SAME
 * seam the REST surface rides ({@code IdentityUserProvider}:
 * {@link UserLookupPort#findBySubject(String)} over the users table's
 * unique {@code subject} column) — one resolution contract for both
 * surfaces, no second, driftier mapping.
 *
 * <p><b>Where it runs:</b> CONNECT only, registered right AFTER the JWT
 * lifter in {@code WebSocketConfig}'s client-inbound chain. The framework
 * associates the CONNECT's final user with every subsequent STOMP message
 * on the session (the documented token-authentication pattern the
 * {@code JwtChannelAuthenticationInterceptor} already rides —
 * {@code setUserChangeCallback} stores the interceptor-set principal for
 * the session, bytecode-verified against spring-websocket 7.0.9), so one
 * translation per connection covers the controller, the subscription
 * guards and the message layer's authorization manager — the three
 * consumers align on the UUID after the boundary. The lookup is one
 * indexed query per connection (the same cost every REST request pays
 * through {@code IdentityUserProvider}).
 *
 * <p><b>Deliberate no-ops (fail-closed pass-through):</b> a principal
 * whose name already parses as a UUID is left untouched (idempotent —
 * future UUID-subject tokens translate themselves); a principal whose
 * subject does not resolve to a users row is left untouched — the token
 * authenticated, but the account has no identity row, and the existing
 * guards keep rejecting it exactly as before (REST's strict path throws
 * for the same state; the WebSocket path stays closed, never silently
 * degraded to a different identity); a non-token principal (the testing
 * tokens of module-slice contexts) and an absent user (the anonymous
 * CONNECT the message layer's authorization manager judges) are not this
 * flow. The port itself resolves lazily through
 * {@link ObjectProvider}: a module-slice context boots without the
 * identity module's implementation and the interceptor is inert there —
 * the same shape the JWT lifter carries.
 */
final class WebSocketIdentityChannelInterceptor implements ChannelInterceptor {

    private final ObjectProvider<UserLookupPort> userLookupPort;

    WebSocketIdentityChannelInterceptor(ObjectProvider<UserLookupPort> userLookupPort) {
        this.userLookupPort = userLookupPort;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Authentication (and therefore identity translation) happens on
            // the CONNECT frame only — the documented pattern.
            return message;
        }
        if (!(accessor.getUser() instanceof JwtAuthenticationToken jwtToken)) {
            // Not a token principal (anonymous CONNECT, or a slice context's
            // testing principal) — not this flow; the message layer's
            // authorization manager decides the CONNECT's fate.
            return message;
        }
        String subject = jwtToken.getName();
        if (isUuid(subject)) {
            // Already the stable id — idempotent, never re-translated.
            return message;
        }
        // The module-slice shape: no identity module implementation boots in
        // the messaging slice — no resolution exists there at all.
        UserLookupPort port = userLookupPort.getIfAvailable();
        if (port == null) {
            return message;
        }
        UUID userId = port.findBySubject(subject)
                .map(UserSummary::id)
                .orElse(null);
        if (userId == null) {
            // Authenticated token, unresolvable account — the fail-closed
            // pass-through (see the class javadoc). The guards keep
            // rejecting: the same state REST answers with its strict
            // path's loud failure, never a silent identity substitution.
            return message;
        }
        // The boundary's first-party principal: the same token
        // authentication wearing the stable user id as its name. The
        // framework stores the CONNECT's final user for the session's
        // subsequent messages (setUserChangeCallback — verified).
        accessor.setUser(new WebSocketUserIdentity(jwtToken, userId.toString()));
        return message;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() < 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}
