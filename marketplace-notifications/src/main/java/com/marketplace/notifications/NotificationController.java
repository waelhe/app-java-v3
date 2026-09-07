package com.marketplace.notifications;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NotificationController {

    private final NotificationService service;
    private final NotificationPreferenceService preferenceService;

    public NotificationController(NotificationService service,
                                   NotificationPreferenceService preferenceService) {
        this.service = service;
        this.preferenceService = preferenceService;
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<Notification>> getMine(Authentication authentication) {
        return ResponseEntity.ok(service.getMyNotifications(authentication));
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<Notification> markRead(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(service.markAsRead(id, authentication));
    }

    /**
     * L22 (feature-expansion roadmap §5, Week 2): the caller's effective
     * notification preference matrix — every type × every channel with the
     * stored override or the enabled default.
     */
    @GetMapping("/notifications/preferences")
    public ResponseEntity<List<NotificationPreferenceView>> getMyPreferences(Authentication authentication) {
        return ResponseEntity.ok(preferenceService.getMyPreferences(authentication));
    }

    /**
     * L22: applies the request's switches (upsert) and returns the resulting
     * effective matrix. "Back to default" is {@code enabled = true} — there
     * is no delete path, so the matrix stays sparse.
     */
    @PutMapping("/notifications/preferences")
    public ResponseEntity<List<NotificationPreferenceView>> updateMyPreferences(
            @Valid @RequestBody NotificationPreferencesUpdateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(preferenceService.updateMyPreferences(authentication, request));
    }
}
