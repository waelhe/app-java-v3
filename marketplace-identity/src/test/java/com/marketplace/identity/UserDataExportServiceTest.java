package com.marketplace.identity;

import com.marketplace.shared.api.BookingExportEntry;
import com.marketplace.shared.api.BookingExportPort;
import com.marketplace.shared.api.ConversationExportEntry;
import com.marketplace.shared.api.MediaExportEntry;
import com.marketplace.shared.api.MediaExportPort;
import com.marketplace.shared.api.MessageExportEntry;
import com.marketplace.shared.api.MessagingExportData;
import com.marketplace.shared.api.MessagingExportPort;
import com.marketplace.shared.api.NotificationExportEntry;
import com.marketplace.shared.api.NotificationExportPort;
import com.marketplace.shared.api.ReviewExportEntry;
import com.marketplace.shared.api.ReviewExportPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDataExportServiceTest {

    private final BookingExportPort bookingExportPort = mock(BookingExportPort.class);
    private final ReviewExportPort reviewExportPort = mock(ReviewExportPort.class);
    private final MessagingExportPort messagingExportPort = mock(MessagingExportPort.class);
    private final MediaExportPort mediaExportPort = mock(MediaExportPort.class);
    private final NotificationExportPort notificationExportPort = mock(NotificationExportPort.class);

    private final UserDataExportService service = new UserDataExportService(
            bookingExportPort, reviewExportPort, messagingExportPort,
            mediaExportPort, notificationExportPort);

    @Test
    void aggregatesEveryModuleShareWithTheBoundaryNoticeAndTheProfile() {
        User user = User.create("sub-export", "exporter@example.com", "Exporter", UserRole.CONSUMER);
        UUID userId = user.getId();

        var bookings = List.of(new BookingExportEntry(UUID.randomUUID(), "CONSUMER",
                UUID.randomUUID(), UUID.randomUUID(), "PENDING", null, null,
                1_000L, "SAR", Instant.now(), Instant.now()));
        var reviews = List.of(new ReviewExportEntry(UUID.randomUUID(), UUID.randomUUID(),
                "CONSUMER_TO_PROVIDER", 5, "great", UUID.randomUUID(), null,
                Instant.now(), Instant.now()));
        var conversations = List.of(new ConversationExportEntry(UUID.randomUUID(),
                UUID.randomUUID(), null, Instant.now(), Instant.now()));
        var messages = List.of(new MessageExportEntry(UUID.randomUUID(),
                UUID.randomUUID(), "hello", Instant.now(), Instant.now()));
        var media = List.of(new MediaExportEntry(UUID.randomUUID(), UUID.randomUUID(),
                "listings/x/y.jpg", "image/jpeg", 10L, "UPLOADED", 1,
                Instant.now(), Instant.now()));
        var notifications = List.of(new NotificationExportEntry(UUID.randomUUID(),
                "BOOKING_CREATED", "Booking created", false, Instant.now(), Instant.now()));

        when(bookingExportPort.exportForParticipant(userId)).thenReturn(bookings);
        when(reviewExportPort.exportForAuthor(userId)).thenReturn(reviews);
        when(messagingExportPort.exportForParticipant(userId))
                .thenReturn(new MessagingExportData(conversations, messages));
        when(mediaExportPort.exportForOwner(userId)).thenReturn(media);
        when(notificationExportPort.exportForRecipient(userId)).thenReturn(notifications);

        UserDataExportResponse response = service.exportFor(user);

        // Every section is the owning module's share, passed through intact.
        assertSame(bookings, response.bookings());
        assertSame(reviews, response.reviews());
        assertSame(conversations, response.conversations());
        assertSame(messages, response.messages());
        assertSame(media, response.media());
        assertSame(notifications, response.notifications());

        // The profile section: the account row's own fields.
        assertEquals(userId, response.profile().id());
        assertEquals("sub-export", response.profile().subject());
        assertEquals("exporter@example.com", response.profile().email());
        assertEquals("Exporter", response.profile().displayName());
        assertEquals("CONSUMER", response.profile().role());

        // The boundary notice (§5-ج): scope + date inside the response.
        assertNotNull(response.export().generatedAt());
        assertEquals(UserDataExportResponse.SCOPE_NOTICE, response.export().scopeNotice());
    }

    @Test
    void emptySharesComposeAnEmptyButCompleteDocument() {
        User user = User.create("sub-empty", null, null, UserRole.CONSUMER);
        UUID userId = user.getId();
        when(bookingExportPort.exportForParticipant(userId)).thenReturn(List.of());
        when(reviewExportPort.exportForAuthor(userId)).thenReturn(List.of());
        when(messagingExportPort.exportForParticipant(userId))
                .thenReturn(new MessagingExportData(List.of(), List.of()));
        when(mediaExportPort.exportForOwner(userId)).thenReturn(List.of());
        when(notificationExportPort.exportForRecipient(userId)).thenReturn(List.of());

        UserDataExportResponse response = service.exportFor(user);

        assertEquals(0, response.bookings().size());
        assertEquals(0, response.reviews().size());
        assertEquals(0, response.conversations().size());
        assertEquals(0, response.messages().size());
        assertEquals(0, response.media().size());
        assertEquals(0, response.notifications().size());
        assertEquals("sub-empty", response.profile().subject());
    }
}
