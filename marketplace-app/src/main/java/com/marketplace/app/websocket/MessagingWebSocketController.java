package com.marketplace.app.websocket;

import com.marketplace.messaging.MessageResponse;
import com.marketplace.messaging.MessagingService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Controller
public class MessagingWebSocketController {

    private final MessagingService messagingService;

    public MessagingWebSocketController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @MessageMapping("/chat.sendMessage/{conversationId}")
    public MessageResponse sendMessage(@DestinationVariable UUID conversationId,
                                       @Payload Map<String, String> payload,
                                       Principal principal) {
        UUID senderId = authenticatedUserId(principal);
        String content = payload.get("content");
        return messagingService.sendMessage(conversationId, senderId, content);
    }

    @MessageMapping("/chat.markRead/{conversationId}")
    public void markRead(@DestinationVariable UUID conversationId,
                         Principal principal) {
        messagingService.markAsRead(conversationId, authenticatedUserId(principal));
    }

    private UUID authenticatedUserId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new AccessDeniedException("Authenticated WebSocket principal is required");
        }
        if (principal instanceof Authentication authentication && !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated WebSocket principal is required");
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Authenticated WebSocket principal must contain a UUID subject", ex);
        }
    }
}
