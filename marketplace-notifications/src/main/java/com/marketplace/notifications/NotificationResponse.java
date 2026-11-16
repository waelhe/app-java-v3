package com.marketplace.notifications;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for an in-app notification — a 1:1 snapshot of
 * {@link Notification}'s JSON surface (entity fields plus the audit columns
 * inherited from the shared {@code BaseEntity}). Introduced so the HTTP
 * boundary stops exposing the JPA entity (audit 2026-09-25,
 * clean-architecture finding 1); {@code @Schema(name)} keeps the OpenAPI
 * schema name stable so the published contract is unchanged.
 */
@Schema(name = "Notification")
public record NotificationResponse(
        UUID id,
        UUID recipientId,
        String type,
        String message,
        boolean read,
        Long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRecipientId(),
                notification.getType(),
                notification.getMessage(),
                notification.isRead(),
                notification.getVersion(),
                notification.getCreatedBy(),
                notification.getCreatedAt(),
                notification.getUpdatedBy(),
                notification.getUpdatedAt());
    }
}
