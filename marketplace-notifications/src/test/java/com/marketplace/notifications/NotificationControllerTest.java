package com.marketplace.notifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService service;

    @Mock
    private NotificationPreferenceService preferenceService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private NotificationController controller;

    @Test
    void getMineReturnsPagedNotifications() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        var page = new org.springframework.data.domain.PageImpl<>(
                List.of(NotificationResponse.from(
                        Notification.create(UUID.randomUUID(), "BOOKING_CREATED", "msg"))), pageable, 1);
        when(service.getMyNotifications(authentication, pageable)).thenReturn(page);

        ResponseEntity<com.marketplace.shared.api.PagedResponse<NotificationResponse>> result =
                controller.getMine(pageable, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void getUnreadCountReturnsTheBadgeNumber() {
        when(service.getUnreadCount(authentication)).thenReturn(5L);

        ResponseEntity<NotificationController.UnreadCountResponse> result =
                controller.getMyUnreadCount(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().unreadCount()).isEqualTo(5L);
    }

    @Test
    void markReadReturnsUpdatedNotification() {
        UUID id = UUID.randomUUID();
        var notification = NotificationResponse.from(
                Notification.create(UUID.randomUUID(), "BOOKING_CREATED", "msg"));
        when(service.markAsRead(id, authentication)).thenReturn(notification);

        ResponseEntity<NotificationResponse> result = controller.markRead(id, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(notification);
    }

    @Test
    void getMyPreferencesReturnsEffectiveMatrix() {
        var matrix = List.of(new NotificationPreferenceView(
                NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false));
        when(preferenceService.getMyPreferences(authentication)).thenReturn(matrix);

        ResponseEntity<List<NotificationPreferenceView>> result = controller.getMyPreferences(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(matrix);
    }

    @Test
    void updateMyPreferencesReturnsEffectiveMatrix() {
        var request = new NotificationPreferencesUpdateRequest(List.of(
                new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false)));
        var matrix = List.of(new NotificationPreferenceView(
                NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false));
        when(preferenceService.updateMyPreferences(authentication, request)).thenReturn(matrix);

        ResponseEntity<List<NotificationPreferenceView>> result =
                controller.updateMyPreferences(request, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(matrix);
    }

    /**
     * B-07 (compliance plan 0.8): the delete answers 204 — the reviews
     * module's house precedent for the delete status.
     */
    @Test
    void deleteReturnsNoContentAndDelegates() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> result = controller.delete(id, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(id, authentication);
    }

    /**
     * B-07 (0.8): the clear-all response carries the marked count.
     */
    @Test
    void markAllReadReturnsTheMarkedCount() {
        when(service.markAllAsRead(authentication)).thenReturn(7);

        ResponseEntity<NotificationController.MarkAllReadResponse> result =
                controller.markAllRead(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().markedRead()).isEqualTo(7L);
    }
}
