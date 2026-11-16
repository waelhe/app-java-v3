package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationType;
import com.marketplace.notifications.WebSocketNotification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * WebSocket leg's executor for the routed deliveries — one push to one
 * recipient's live broker topic.
 *
 * <p><b>The WebSocket is NOT the mobile push (§8.1 verbatim):</b> this
 * component publishes to the same {@code /topic/notifications/{userId}}
 * broker topic the standing {@code NotificationService} leg uses — it
 * reaches a LIVE web session subscribed to that topic, and nothing else.
 * The gate lives UPSTREAM here: the routing engine's WS decision is the
 * only gate (the L22 WS-preference check inside
 * {@code NotificationService.sendWebSocket} serves the standing paths
 * that consult it directly) — a routed delivery arrives here only after
 * the engine already honored the preference hierarchy.
 */
@Component
public class UrgentAlertWebSocketPush {

    private final Optional<SimpMessagingTemplate> messagingTemplate;

    public UrgentAlertWebSocketPush(Optional<SimpMessagingTemplate> messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Pushes one routed notification to the recipient's broker topic.
     * A missing broker (development profiles) is the honest no-op — the
     * same Optional shape the standing leg rides.
     */
    public void push(UUID recipientId, NotificationType type, String message) {
        messagingTemplate.ifPresent(template ->
                template.convertAndSend("/topic/notifications/" + recipientId,
                        new WebSocketNotification(type.name(), message)));
    }
}
