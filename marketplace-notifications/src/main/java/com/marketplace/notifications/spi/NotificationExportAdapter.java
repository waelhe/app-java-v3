package com.marketplace.notifications.spi;

import com.marketplace.notifications.NotificationRepository;
import com.marketplace.shared.api.NotificationExportEntry;
import com.marketplace.shared.api.NotificationExportPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the notifications module's implementation of the
 * {@link NotificationExportPort} cross-module contract. A read-only
 * delegation to the module's own recipient-scoped read (the table is
 * recipient-only by design, V18) — the plan's own provenance note.
 */
@Component
@Transactional(readOnly = true)
public class NotificationExportAdapter implements NotificationExportPort {

    private final NotificationRepository notificationRepository;

    public NotificationExportAdapter(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public List<NotificationExportEntry> exportForRecipient(UUID userId) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(notification -> new NotificationExportEntry(
                        notification.getId(),
                        notification.getType(),
                        notification.getMessage(),
                        notification.isRead(),
                        notification.getCreatedAt(),
                        notification.getUpdatedAt()))
                .toList();
    }
}
