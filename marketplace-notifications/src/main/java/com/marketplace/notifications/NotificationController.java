package com.marketplace.notifications;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
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

    /**
     * Plan item 2.6: the feed is paginated — the repository's own standard
     * pattern ({@code PagedResponse.of(...)} over a Spring Data page, the
     * BookingController/MessagingController precedent). {@code @ParameterObject}
     * is springdoc's official mechanism that renders the Pageable as the
     * three OPTIONAL query parameters (page/size/sort) instead of one
     * opaque required object — measured from the springdoc 3.1.0 sources
     * themselves (org.springdoc.core.converters.models.Pageable: every
     * field optional, defaults 0/20). The OpenAPI gate was simulated
     * end-to-end BEFORE this push: openapi-diff 2.1.7 on the REAL production
     * spec pair with exactly this change answers "API changes are backward
     * compatible" (exit 0) — the array-to-PagedResponse shape and the
     * optional parameter additions are officially compatible, so no
     * allowlist entry is needed.
     */
    @GetMapping("/notifications")
    @Operation(summary = "List my notifications", description = "The caller's in-app "
            + "notification feed, newest first, paginated (page/size/sort).")
    public ResponseEntity<PagedResponse<Notification>> getMine(
            @ParameterObject Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                service.getMyNotifications(authentication, pageable)));
    }

    /**
     * Plan item 2.6: the unread badge count — the polling endpoint's single
     * number (the MessagingController unread-count precedent, same response
     * shape).
     */
    @GetMapping("/notifications/unread-count")
    @Operation(summary = "Count my unread notifications",
            description = "The caller's unread in-app notification count — the badge "
                    + "polling endpoint.")
    public ResponseEntity<UnreadCountResponse> getMyUnreadCount(Authentication authentication) {
        return ResponseEntity.ok(new UnreadCountResponse(service.getUnreadCount(authentication)));
    }

    @PostMapping("/notifications/{id}/read")
    @Operation(summary = "Mark a notification read", description = "Marks one in-app "
            + "notification as read by its owner.")
    public ResponseEntity<Notification> markRead(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(service.markAsRead(id, authentication));
    }

    /**
     * L22 (feature-expansion roadmap §5, Week 2): the caller's effective
     * notification preference matrix — every type × every channel with the
     * stored override or the enabled default.
     */
    @GetMapping("/notifications/preferences")
    @Operation(summary = "Get my notification preferences",
            description = "The caller's effective preference matrix — every notification type "
                    + "× every channel with the stored override or the enabled default.")
    public ResponseEntity<List<NotificationPreferenceView>> getMyPreferences(Authentication authentication) {
        return ResponseEntity.ok(preferenceService.getMyPreferences(authentication));
    }

    /**
     * L22: applies the request's switches (upsert) and returns the resulting
     * effective matrix. "Back to default" is {@code enabled = true} — there
     * is no delete path, so the matrix stays sparse.
     */
    @PutMapping("/notifications/preferences")
    @Operation(summary = "Update my notification preferences",
            description = "Applies the requested switches (upsert) and returns the resulting "
                    + "effective matrix. \"Back to default\" is enabled = true.")
    public ResponseEntity<List<NotificationPreferenceView>> updateMyPreferences(
            @Valid @RequestBody NotificationPreferencesUpdateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(preferenceService.updateMyPreferences(authentication, request));
    }

    /** Plan item 2.6: the badge shape (the MessagingController precedent). */
    @Schema(description = "Unread badge count")
    public record UnreadCountResponse(
            @Schema(description = "Unread notifications for the caller", example = "3")
            long unreadCount
    ) {
    }
}
