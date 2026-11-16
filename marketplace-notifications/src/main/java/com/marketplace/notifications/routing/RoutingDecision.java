package com.marketplace.notifications.routing;

import java.util.Set;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * routing engine's answer for ONE recipient — which channels deliver and
 * which gates suppressed the rest.
 *
 * <p>The four channels are the §8.1 separate policies, each its own
 * boolean: the in-app inbox, the email, the WebSocket and the push.
 * <b>The WebSocket is NOT the mobile push</b> (§8.1 verbatim) — a WS
 * broadcast reaches a live web session's broker topic; the push leg is
 * the {@code PushNotificationChannel} seam awaiting the provider
 * decision (D-10). A channel that delivers carries no suppression; the
 * {@code suppressions} set explains every channel that does not.
 *
 * @param recipientId the one recipient this decision answers (the
 *                    decision is per-recipient by construction — a
 *                    fan-out is many decisions, never one shared answer)
 * @param inbox       the in-app inbox row (the L22 always-on channel)
 * @param email       the email channel
 * @param webSocket   the WebSocket broker topic (not the mobile push)
 * @param push        the mobile push channel (absent until D-10 lands)
 * @param suppressions the gates that held channels back (explainability)
 */
public record RoutingDecision(
        UUID recipientId,
        boolean inbox,
        boolean email,
        boolean webSocket,
        boolean push,
        Set<RoutingSuppression> suppressions) {

    public RoutingDecision {
        suppressions = Set.copyOf(suppressions);
    }

    /** The all-channels answer for a policy that passed every gate. */
    static RoutingDecision delivered(UUID recipientId, boolean email, boolean webSocket,
                                     boolean push) {
        return new RoutingDecision(recipientId, true, email, webSocket, push, Set.of());
    }

    /** The no-channels answer carrying WHY — one gate, the whole answer. */
    static RoutingDecision suppressed(UUID recipientId, RoutingSuppression reason) {
        return new RoutingDecision(recipientId, false, false, false, false, Set.of(reason));
    }
}
