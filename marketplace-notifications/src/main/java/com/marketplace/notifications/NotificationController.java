package com.marketplace.notifications;

import com.marketplace.shared.api.ApiConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for user notifications.
 *
 * <p>Class-level {@code @PreAuthorize("isAuthenticated()")} ensures all endpoints
 * require authentication.
 *
 * <p>Reference: Spring Security Reference -- Method Security:
 * "If it is at the class level... then all methods inherit the class-level behavior."
 * https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<Notification>> getMine(Authentication authentication) {
        return ResponseEntity.ok(service.getMyNotifications(authentication));
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<Notification> markRead(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(service.markAsRead(id, authentication));
    }
}
