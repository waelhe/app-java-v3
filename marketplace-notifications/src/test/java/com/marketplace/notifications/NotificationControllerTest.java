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
    private Authentication authentication;

    @InjectMocks
    private NotificationController controller;

    @Test
    void getMineReturnsNotificationList() {
        var notifications = List.of(mock(Notification.class));
        when(service.getMyNotifications(authentication)).thenReturn(notifications);

        ResponseEntity<List<Notification>> result = controller.getMine(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(notifications);
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
}
