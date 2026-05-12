package com.marketplace.notifications;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.NOTIFICATIONS, version = "1.0")
public class NotificationController {
    private final NotificationService notificationService;
    public NotificationController(NotificationService notificationService) { this.notificationService = notificationService; }

    @GetMapping
    public ResponseEntity<PagedResponse<NotificationResponse>> list(Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(notificationService.listMine(pageable, authentication).map(NotificationResponse::from)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markRead(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(NotificationResponse.from(notificationService.markRead(id, authentication)));
    }
}
