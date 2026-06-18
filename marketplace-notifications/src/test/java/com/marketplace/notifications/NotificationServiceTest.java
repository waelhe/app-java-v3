package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.email.EmailService;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private static final UUID CONSUMER_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final String CONSUMER_EMAIL = "consumer@test.com";
    private static final String PROVIDER_EMAIL = "provider@test.com";

    private NotificationService createService(NotificationRepository repository,
                                              BookingParticipantProvider bookingProvider,
                                              PaymentIntentLookupPort paymentIntentLookupPort,
                                              CurrentUserProvider currentUserProvider,
                                              UserLookupPort userLookupPort,
                                              Optional<SimpMessagingTemplate> messagingTemplate,
                                              Optional<EmailService> emailService) {
        return new NotificationService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, messagingTemplate, emailService, "http://localhost:8080");
    }

    private UserLookupPort mockUserLookup() {
        UserLookupPort lookup = mock(UserLookupPort.class);
        when(lookup.findById(any())).thenReturn(Optional.empty());
        when(lookup.findById(CONSUMER_ID)).thenReturn(Optional.of(
                new UserSummary(CONSUMER_ID, CONSUMER_EMAIL, "Consumer", "CONSUMER", Instant.now(), Instant.now())));
        when(lookup.findById(PROVIDER_ID)).thenReturn(Optional.of(
                new UserSummary(PROVIDER_ID, PROVIDER_EMAIL, "Provider", "PROVIDER", Instant.now(), Instant.now())));
        return lookup;
    }

    private BookingInfo bookingInfo() {
        return new BookingInfo(PROVIDER_ID, CONSUMER_ID, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
    }

    @Test
    void onBookingCreatedCreatesTwoNotifications() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID bookingId = create(UUID.class);
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onBookingCreated(bookingId);

        verify(repository, times(2)).save(any(Notification.class));
    }

    @Test
    void onBookingCreatedSendsEmailAndWebSocket() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        EmailService emailService = mock(EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID bookingId = create(UUID.class);
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onBookingCreated(bookingId);

        verify(emailService, times(2)).send(anyString(), anyString(), anyString(), anyMap());
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(WebSocketNotification.class));
    }

    @Test
    void markReadMarksNotificationForOwner() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID userId = create(UUID.class);
        Notification notification = of(Notification.class)
                .set(field(Notification::getRecipientId), userId)
                .set(field(Notification::getType), "BOOKING_CREATED")
                .set(field(Notification::getMessage), "msg")
                .create();
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        Notification updated = service.markAsRead(notification.getId(), authentication);

        assertThat(updated.isRead()).isTrue();
    }

    @Test
    void onPaymentStateChangedCreatesNotificationsForConsumerAndProvider() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID paymentIntentId = create(UUID.class);
        UUID bookingId = create(UUID.class);

        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(of(PaymentIntentDetails.class)
                        .set(field(PaymentIntentDetails::paymentIntentId), paymentIntentId)
                        .set(field(PaymentIntentDetails::bookingId), bookingId)
                        .set(field(PaymentIntentDetails::consumerId), CONSUMER_ID)
                        .create()));
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(repository, times(2)).save(any(Notification.class));
    }

    @Test
    void markReadThrowsWhenNotFound() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.marketplace.shared.api.ResourceNotFoundException.class,
                () -> service.markAsRead(id, authentication)
        );
    }

    @Test
    void markReadThrowsAccessDeniedForNonOwnerNonAdmin() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID ownerId = UUID.randomUUID();
        UUID differentUserId = UUID.randomUUID();
        Notification notification = mock(Notification.class);
        when(notification.getRecipientId()).thenReturn(ownerId);
        when(repository.findById(any())).thenReturn(Optional.of(notification));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(differentUserId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.markAsRead(UUID.randomUUID(), authentication)
        );
    }

    @Test
    void markReadAllowsAdminEvenWhenNotOwner() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID ownerId = UUID.randomUUID();
        UUID differentUserId = UUID.randomUUID();
        Notification notification = mock(Notification.class);
        when(notification.getRecipientId()).thenReturn(ownerId);
        when(repository.findById(any())).thenReturn(Optional.of(notification));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(differentUserId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);

        service.markAsRead(UUID.randomUUID(), authentication);

        verify(notification).markRead();
    }

    @Test
    void onPaymentStateChangedWithEmptyOptionalDoesNothing() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID paymentIntentId = UUID.randomUUID();
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.empty());

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(repository, never()).save(any());
    }

    @Test
    void getMyNotificationsReturnsNotificationsForCurrentUser() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID userId = UUID.randomUUID();
        var notifications = List.of(mock(Notification.class));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(repository.findByRecipientIdOrderByCreatedAtDesc(userId)).thenReturn(notifications);

        var result = service.getMyNotifications(authentication);

        assertThat(result).isSameAs(notifications);
    }

    @Test
    void onPaymentStateChangedSendsEmailAndWebSocket() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        EmailService emailService = mock(EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID paymentIntentId = create(UUID.class);
        UUID bookingId = create(UUID.class);

        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(of(PaymentIntentDetails.class)
                        .set(field(PaymentIntentDetails::paymentIntentId), paymentIntentId)
                        .set(field(PaymentIntentDetails::bookingId), bookingId)
                        .set(field(PaymentIntentDetails::consumerId), CONSUMER_ID)
                        .create()));
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(emailService, times(2)).send(anyString(), anyString(), anyString(), anyMap());
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(WebSocketNotification.class));
    }
}
