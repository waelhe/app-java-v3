package com.marketplace.notifications;

import java.util.UUID;

public record NotificationResponse(UUID id, UUID recipientId, String channel, String subject, String body, boolean read) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getRecipientId(), notification.getChannel().name(), notification.getSubject(), notification.getBody(), notification.isRead());
    }
}
