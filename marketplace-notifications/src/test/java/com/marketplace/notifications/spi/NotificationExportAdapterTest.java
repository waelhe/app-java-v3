package com.marketplace.notifications.spi;

import com.marketplace.notifications.Notification;
import com.marketplace.notifications.NotificationRepository;
import com.marketplace.shared.api.NotificationExportEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationExportAdapterTest {

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationExportAdapter adapter = new NotificationExportAdapter(notificationRepository);

    @Test
    void exportsTheRecipientScopedRows() {
        UUID me = UUID.randomUUID();
        Notification notification = Notification.create(me, "BOOKING_CREATED",
                "Booking created: 123");
        when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(me))
                .thenReturn(List.of(notification));

        List<NotificationExportEntry> entries = adapter.exportForRecipient(me);

        assertEquals(1, entries.size());
        NotificationExportEntry entry = entries.get(0);
        assertEquals(notification.getId(), entry.id());
        assertEquals("BOOKING_CREATED", entry.type());
        assertEquals("Booking created: 123", entry.message());
        assertFalse(entry.read());
        assertEquals(6, NotificationExportEntry.class.getRecordComponents().length);
    }
}
