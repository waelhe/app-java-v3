package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
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
                                              Optional<com.marketplace.shared.email.EmailService> emailService) {
        return createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, messagingTemplate, emailService,
                defaultPreferences());
    }

    private NotificationService createService(NotificationRepository repository,
                                              BookingParticipantProvider bookingProvider,
                                              PaymentIntentLookupPort paymentIntentLookupPort,
                                              CurrentUserProvider currentUserProvider,
                                              UserLookupPort userLookupPort,
                                              Optional<SimpMessagingTemplate> messagingTemplate,
                                              Optional<com.marketplace.shared.email.EmailService> emailService,
                                              NotificationPreferenceService preferences) {
        EmailNotificationService emailNotificationService = new EmailNotificationService(emailService, userLookupPort);
        // B-11: the REAL text source — the production bundles resolve on
        // this module's own classpath, so the delivery tests pin the
        // platform-locale (Arabic) composition exactly as production
        // composes it (the locale pair itself is proven in
        // NotificationTextSourceTest).
        return new NotificationService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, emailNotificationService, messagingTemplate, preferences,
                new NotificationTextSource());
    }

    /**
     * L22 default gate: no override anywhere — every channel enabled. With
     * this the pre-L22 tests keep meaning "the default is exactly the old
     * behavior" (roadmap acceptance criterion 2).
     */
    private NotificationPreferenceService defaultPreferences() {
        NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
        when(preferences.isChannelEnabled(any(), any(), any())).thenReturn(true);
        return preferences;
    }

    private UserLookupPort mockUserLookup() {
        UserLookupPort lookup = mock(UserLookupPort.class);
        when(lookup.findById(any())).thenReturn(Optional.empty());
        when(lookup.findById(CONSUMER_ID)).thenReturn(Optional.of(
                new UserSummary(CONSUMER_ID, CONSUMER_EMAIL, "Consumer", "CONSUMER", Instant.now(), Instant.now(), null)));
        when(lookup.findById(PROVIDER_ID)).thenReturn(Optional.of(
                new UserSummary(PROVIDER_ID, PROVIDER_EMAIL, "Provider", "PROVIDER", Instant.now(), Instant.now(), null)));
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

    /**
     * B-11 (compliance plan B.6): the delivery journey composes at the
     * platform locale — the in-app row, the email subject/body, and the WS
     * payload all carry the ARABIC rendering of the same fact, with the
     * booking id riding the MessageFormat argument (the machine facts
     * unchanged: type enum, template name, topic destination).
     */
    @Test
    void onBookingCreatedComposesAtThePlatformLocale() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID bookingId = create(UUID.class);
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onBookingCreated(bookingId);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getMessage())
                .isEqualTo("تم إنشاء الحجز: " + bookingId);
        assertThat(saved.getAllValues().get(1).getMessage())
                .isEqualTo("طلب حجز جديد: " + bookingId);
        verify(emailService).send(eq(CONSUMER_EMAIL), eq("تم إنشاء الحجز"),
                eq("email/notification"), eq(java.util.Map.of("message", "تم إنشاء حجزك " + bookingId + ".")));
        verify(emailService).send(eq(PROVIDER_EMAIL), eq("طلب حجز جديد"),
                eq("email/notification"), eq(java.util.Map.of("message", "طلب حجز جديد " + bookingId + " لخدمتك.")));
    }

    @Test
    void onBookingCreatedSendsEmailAndWebSocket() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
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
    void onPaymentStateChangedSuppressesEmailForUnsubscribedConsumer() {
        // L22 acceptance criterion 1: the consumer unsubscribed from EMAIL
        // for PAYMENT_STATE => the in-app notification is created, WS is
        // pushed, and NO email call is made for the consumer — while the
        // provider's email still goes (interaction verified).
        NotificationPreferenceService preferences = defaultPreferences();
        when(preferences.isChannelEnabled(CONSUMER_ID, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL))
                .thenReturn(false);
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService),
                preferences);

        UUID paymentIntentId = create(UUID.class);
        UUID bookingId = create(UUID.class);
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(of(PaymentIntentDetails.class)
                        .set(field(PaymentIntentDetails::paymentIntentId), paymentIntentId)
                        .set(field(PaymentIntentDetails::bookingId), bookingId)
                        .set(field(PaymentIntentDetails::consumerId), CONSUMER_ID)
                        .set(field(PaymentIntentDetails::origin), PaymentIntentDetails.ORIGIN_BOOKING)
                        .create()));
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(emailService, never()).send(eq(CONSUMER_EMAIL), anyString(), anyString(), anyMap());
        verify(emailService, times(1)).send(eq(PROVIDER_EMAIL), anyString(), anyString(), anyMap());
        verify(repository, times(2)).save(any(Notification.class));
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(WebSocketNotification.class));
    }

    @Test
    void onBookingCreatedSuppressesWebSocketForUnsubscribedRecipients() {
        // L22: WS sends by default and honors an explicit opt-out — both
        // recipients unsubscribed from WS for BOOKING_CREATED => no push at
        // all, while the in-app rows land and both emails still go.
        NotificationPreferenceService preferences = defaultPreferences();
        when(preferences.isChannelEnabled(any(), eq(NotificationType.BOOKING_CREATED), eq(NotificationChannel.WS)))
                .thenReturn(false);
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService),
                preferences);

        UUID bookingId = create(UUID.class);
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onBookingCreated(bookingId);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(WebSocketNotification.class));
        verify(emailService, times(2)).send(anyString(), anyString(), anyString(), anyMap());
        verify(repository, times(2)).save(any(Notification.class));
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

        NotificationResponse updated = service.markAsRead(notification.getId(), authentication);

        assertThat(updated.read()).isTrue();
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
                        .set(field(PaymentIntentDetails::origin), PaymentIntentDetails.ORIGIN_BOOKING)
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
    void getMyNotificationsReturnsPagedNotificationsForCurrentUser() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID userId = UUID.randomUUID();
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        var page = new org.springframework.data.domain.PageImpl<>(
                List.of(Notification.create(userId, "BOOKING_CREATED", "msg")), pageable, 1);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(repository.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)).thenReturn(page);

        var result = service.getMyNotifications(authentication, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).recipientId()).isEqualTo(userId);
        assertThat(result.getContent().get(0).type()).isEqualTo("BOOKING_CREATED");
    }

    @Test
    void getUnreadCountCountsOnlyTheCallersUnreadRows() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(repository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(4L);

        assertThat(service.getUnreadCount(authentication)).isEqualTo(4L);
    }

    @Test
    void onPaymentStateChangedSendsEmailAndWebSocket() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
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
                        .set(field(PaymentIntentDetails::origin), PaymentIntentDetails.ORIGIN_BOOKING)
                        .create()));
        when(bookingProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo());

        service.onPaymentStateChanged(paymentIntentId, "COMPLETED");

        verify(emailService, times(2)).send(anyString(), anyString(), anyString(), anyMap());
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(WebSocketNotification.class));
    }

    @Test
    void onLeadReceivedAlertsTheListingProviderUserDirectly() {
        // L34 (realestate systems plan §5): the recipient is the listing's
        // provider id — which lives in the users.id space (the A1/V2 fact,
        // the same seam onBookingCreated uses) — on every channel the
        // default preferences leave on.
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID leadId = create(UUID.class);
        UUID listingId = create(UUID.class);

        service.onLeadReceived(leadId, listingId, PROVIDER_ID);

        verify(repository, times(1)).save(any(Notification.class));
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + PROVIDER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(PROVIDER_EMAIL), anyString(), anyString(), anyMap());
    }

    @Test
    void onPostCommentedAlertsThePostAuthorOnEveryDefaultChannel() {
        // L42 (neighborhood community plan §5): the recipient is the post's
        // author id — the users.id space, the same seam onLeadReceived uses.
        // The self-comment skip is the LISTENER's policy; this method
        // delivers unconditionally, so the delivery contract stays one
        // shape for every caller.
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID postId = create(UUID.class);

        service.onPostCommented(postId, PROVIDER_ID);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("POST_COMMENTED");
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + PROVIDER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(PROVIDER_EMAIL), anyString(), anyString(), anyMap());
    }

    @Test
    void onNewListingInNeighborhoodAlertsTheMemberOnEveryDefaultChannel() {
        // L46 (neighborhood community plan §5): the recipient is the
        // membership's user id — the users.id space, the same seam
        // onSavedSearchMatch uses. The publisher's own exclusion is the
        // COMMUNITY side's bridge policy; this method delivers
        // unconditionally, so the delivery contract stays one shape for
        // every caller.
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID listingId = create(UUID.class);

        service.onNewListingInNeighborhood(PROVIDER_ID, listingId);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("NEW_LISTING_IN_NEIGHBORHOOD");
        assertThat(saved.getValue().getMessage()).contains(listingId.toString());
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + PROVIDER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(PROVIDER_EMAIL), anyString(), anyString(), anyMap());
    }

    @Test
    void onContentModeratedAlertsTheContentAuthorOnEveryDefaultChannel() {
        // L45 (neighborhood community plan §5): the recipient is the
        // moderated content's author id — the users.id space, the same
        // seam onPostCommented uses. The one-real-hide-one-alert policy
        // is the COMMUNITY side's resolve command's; this method delivers
        // unconditionally, so the delivery contract stays one shape for
        // every caller.
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID targetId = create(UUID.class);

        service.onContentModerated(PROVIDER_ID, "POST", targetId);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("CONTENT_MODERATED");
        // B-11: the community vocabulary word renders in Arabic at the
        // platform locale — the pre-B-11 English literal is the default
        // bundle's own entry (proven in NotificationTextSourceTest).
        assertThat(saved.getValue().getMessage())
                .isEqualTo("تمت مراجعة منشور الخاص بك: " + targetId);
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + PROVIDER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(PROVIDER_EMAIL), anyString(), anyString(), anyMap());
    }

    @Test
    void onPostReactedAlertsThePostAuthorOnEveryDefaultChannel() {
        // L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions
        // layer): the recipient is the thanked post's author id — the
        // users.id space, the same seam onPostCommented uses. The
        // self-thank skip is the LISTENER's policy; this method delivers
        // unconditionally, so the delivery contract stays one shape for
        // every caller.
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID postId = create(UUID.class);

        service.onPostReacted(postId, PROVIDER_ID);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("POST_REACTED");
        assertThat(saved.getValue().getMessage()).contains(postId.toString());
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + PROVIDER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(PROVIDER_EMAIL), anyString(), anyString(), anyMap());
    }

    @Test
    void onFollowedProviderNewListingAlertsTheFollowerOnEveryDefaultChannel() {
        // W4 (yelp-level plan §5 — G21): the recipient is the follower's
        // user id — the users.id space, the same seam
        // onNewListingInNeighborhood uses. The exactly-once scoping is the
        // IDENTITY side's bridge + alert ledger; this method delivers
        // unconditionally, so the delivery contract stays one shape for
        // every caller.
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID listingId = create(UUID.class);

        service.onFollowedProviderNewListing(CONSUMER_ID, listingId);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("FOLLOWED_PROVIDER_NEW_LISTING");
        assertThat(saved.getValue().getMessage()).contains(listingId.toString());
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + CONSUMER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(CONSUMER_EMAIL), anyString(), anyString(), anyMap());
    }

    @Test
    void onFollowedProviderNewListingHonorsTheFollowerPreferenceOptOut() {
        // The plan's W4 acceptance: "المتابعة تطلق تنبيهًا واحدًا محترمًا
        // للتفضيل" — the L22 matrix gates the push channels per
        // (follower, FOLLOWED_PROVIDER_NEW_LISTING, channel); the in-app
        // row always lands (the roadmap's "inside the app always").
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
        when(preferences.isChannelEnabled(any(), any(), any())).thenReturn(true);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING),
                eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING),
                eq(NotificationChannel.WS))).thenReturn(false);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService),
                preferences);

        service.onFollowedProviderNewListing(CONSUMER_ID, create(UUID.class));

        verify(repository, times(1)).save(any(Notification.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(WebSocketNotification.class));
        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    /**
     * B-07 (compliance plan 0.8 — the measured defect §3.4-6): delete one
     * notification — the owner's own row leaves the feed (the soft delete
     * on the BaseEntity, so the audit trace survives).
     */
    @Test
    void deleteRemovesTheOwnersNotification() {
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

        service.delete(notification.getId(), authentication);

        verify(repository).delete(notification);
    }

    /**
     * B-07 (0.8): the delete ownership discipline is markRead's — someone
     * else's notification is a 403, and no delete happens.
     */
    @Test
    void deleteThrowsAccessDeniedForNonOwnerNonAdmin() {
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
                () -> service.delete(UUID.randomUUID(), authentication)
        );
        verify(repository, never()).delete(any(Notification.class));
    }

    /**
     * B-07 (0.8): the clear-all — one bulk UPDATE over the CALLER's unread
     * rows; the count comes back for the badge's immediate reconciliation.
     */
    @Test
    void markAllAsReadBulkUpdatesTheCallersRows() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        Authentication authentication = mock(Authentication.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.empty(), Optional.empty());

        UUID userId = create(UUID.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(repository.markAllAsReadByRecipientId(userId)).thenReturn(7);

        int marked = service.markAllAsRead(authentication);

        assertThat(marked).isEqualTo(7);
        verify(repository).markAllAsReadByRecipientId(userId);
    }

    /**
     * B-08 (compliance plan 0.10 — the §3.4-8 defect): the arrival
     * notification — the in-app row always lands (the conversation id in
     * the message body), the push channels ride the L22 matrix with the
     * defaults on. The handler itself is wired to the MessageReceivedEvent
     * listener via CR-4 (the event type's cross-module placement).
     */
    @Test
    void onMessageReceivedDeliversTheArrivalNotification() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID conversationId = create(UUID.class);

        service.onMessageReceived(conversationId, CONSUMER_ID);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("MESSAGE_RECEIVED");
        assertThat(saved.getValue().getMessage()).contains(conversationId.toString());
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + CONSUMER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(CONSUMER_EMAIL), anyString(), anyString(), anyMap());
    }

    /**
     * B-08 (0.10): the recipient's preference opt-out — the L22 matrix
     * gates the push channels per (recipient, MESSAGE_RECEIVED, channel);
     * the in-app row always lands ("inside the app always").
     */
    @Test
    void onMessageReceivedHonorsTheRecipientPreferenceOptOut() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
        when(preferences.isChannelEnabled(any(), any(), any())).thenReturn(true);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.MESSAGE_RECEIVED),
                eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.MESSAGE_RECEIVED),
                eq(NotificationChannel.WS))).thenReturn(false);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService),
                preferences);

        service.onMessageReceived(create(UUID.class), CONSUMER_ID);

        verify(repository, times(1)).save(any(Notification.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(WebSocketNotification.class));
        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    /**
     * B-17 (compliance plan C.9 — the trust &amp; verification sidecar):
     * the member's VERIFIED notification — the in-app row always lands
     * (the neighborhood id in the message body, the Arabic composition
     * riding the B-11 channel), the push channels ride the L22 matrix
     * with the defaults on. The handler itself is wired to the
     * MembershipVerificationGrantedEvent listener (LANDED — the event
     * type's cross-module placement — the B-08/CR-4 flow verbatim).
     */
    @Test
    void onVerificationGrantedDeliversTheVerifiedNotification() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID locationId = create(UUID.class);

        service.onVerificationGranted(CONSUMER_ID, locationId);

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("MEMBERSHIP_VERIFIED");
        assertThat(saved.getValue().getMessage()).contains(locationId.toString());
        assertThat(saved.getValue().getMessage()).contains("تم توثيق عضويتك");
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + CONSUMER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(CONSUMER_EMAIL), anyString(), anyString(), anyMap());
    }

    /**
     * B-17 (C.9): the member's preference opt-out — the L22 matrix gates
     * the push channels per (recipient, MEMBERSHIP_VERIFIED, channel);
     * the in-app row always lands ("inside the app always").
     */
    @Test
    void onVerificationGrantedHonorsTheMemberPreferenceOptOut() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
        when(preferences.isChannelEnabled(any(), any(), any())).thenReturn(true);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.MEMBERSHIP_VERIFIED),
                eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.MEMBERSHIP_VERIFIED),
                eq(NotificationChannel.WS))).thenReturn(false);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService),
                preferences);

        service.onVerificationGranted(CONSUMER_ID, create(UUID.class));

        verify(repository, times(1)).save(any(Notification.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(WebSocketNotification.class));
        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    /**
     * B-17 (compliance plan C.9): the REPORTER's adjudication
     * notification — the in-app row always lands with the composed
     * Arabic adjudication text (the target word and the outcome word
     * rendered through the bundle's vocabulary channel, the target id
     * carried as the fact), the push channels ride the L22 matrix with
     * the defaults on. The handler itself is wired to the
     * ContentReportResolvedEvent listener (LANDED — the B-08/CR-4 flow
     * verbatim).
     */
    @Test
    void onReportResolvedDeliversTheAdjudicationNotification() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService));

        UUID targetId = create(UUID.class);

        service.onReportResolved(CONSUMER_ID, "POST", targetId, "RESOLVED");

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("REPORT_RESOLVED");
        assertThat(saved.getValue().getMessage()).contains("منشور");
        assertThat(saved.getValue().getMessage()).contains("تم اتخاذ إجراء");
        assertThat(saved.getValue().getMessage()).contains(targetId.toString());
        verify(messagingTemplate, times(1)).convertAndSend(
                eq("/topic/notifications/" + CONSUMER_ID), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(CONSUMER_EMAIL), anyString(), anyString(), anyMap());
    }

    /**
     * B-17 (C.9): the reporter's preference opt-out — the L22 matrix
     * gates the push channels per (reporter, REPORT_RESOLVED, channel);
     * the in-app row always lands. The DISMISSED outcome word renders
     * through the same vocabulary channel (every outcome is the
     * reporter's journey's arrival).
     */
    @Test
    void onReportResolvedHonorsTheReporterPreferenceOptOut() {
        NotificationRepository repository = mock(NotificationRepository.class);
        BookingParticipantProvider bookingProvider = mock(BookingParticipantProvider.class);
        PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        UserLookupPort userLookupPort = mockUserLookup();
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        com.marketplace.shared.email.EmailService emailService = mock(com.marketplace.shared.email.EmailService.class);
        NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
        when(preferences.isChannelEnabled(any(), any(), any())).thenReturn(true);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.REPORT_RESOLVED),
                eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(preferences.isChannelEnabled(eq(CONSUMER_ID), eq(NotificationType.REPORT_RESOLVED),
                eq(NotificationChannel.WS))).thenReturn(false);
        NotificationService service = createService(repository, bookingProvider, paymentIntentLookupPort,
                currentUserProvider, userLookupPort, Optional.of(messagingTemplate), Optional.of(emailService),
                preferences);

        service.onReportResolved(CONSUMER_ID, "COMMENT", create(UUID.class), "DISMISSED");

        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getMessage()).contains("تم رفض البلاغ");
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(WebSocketNotification.class));
        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }
}
