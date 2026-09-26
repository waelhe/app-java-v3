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
                List.of(mock(Notification.class)), pageable, 1);
        when(service.getMyNotifications(authentication, pageable)).thenReturn(page);

        ResponseEntity<com.marketplace.shared.api.PagedResponse<Notification>> result =
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
        var notification = mock(Notification.class);
        when(service.markAsRead(id, authentication)).thenReturn(notification);

        ResponseEntity<Notification> result = controller.markRead(id, authentication);

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
}
