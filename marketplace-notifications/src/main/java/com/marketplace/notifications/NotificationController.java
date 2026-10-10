package com.marketplace.notifications;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
    private final com.marketplace.notifications.routing.NotificationTopicPreferenceService topicPreferenceService;
    private final com.marketplace.notifications.routing.NotificationGeoSubscriptionService geoSubscriptionService;

    public NotificationController(NotificationService service,
                                   NotificationPreferenceService preferenceService,
                                   com.marketplace.notifications.routing.NotificationTopicPreferenceService topicPreferenceService,
                                   com.marketplace.notifications.routing.NotificationGeoSubscriptionService geoSubscriptionService) {
        this.service = service;
        this.preferenceService = preferenceService;
        this.topicPreferenceService = topicPreferenceService;
        this.geoSubscriptionService = geoSubscriptionService;
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
    public ResponseEntity<PagedResponse<NotificationResponse>> getMine(
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
    public ResponseEntity<NotificationResponse> markRead(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(service.markAsRead(id, authentication));
    }

    /**
     * B-07 (compliance plan 0.8 — the measured defect §3.4-6): delete one
     * in-app notification — the recipient's own (or an admin's), 204 on
     * success (the reviews module's {@code unvoteHelpful} house precedent
     * for the delete status), the BaseEntity soft delete so the audit
     * trace survives.
     */
    @DeleteMapping("/notifications/{id}")
    @Operation(summary = "Delete a notification", description = "Soft-deletes one in-app "
            + "notification owned by the caller (404 unknown, 403 someone else's).")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * B-07 (0.8): the feed's clear-all — one bulk UPDATE over the CALLER's
     * unread rows; the response carries the count marked so the badge
     * reconciles immediately.
     */
    @PostMapping("/notifications/read-all")
    @Operation(summary = "Mark all my notifications as read", description = "Marks every "
            + "unread in-app notification of the CALLER as read in one bulk update — the "
            + "clear-all action; the response carries how many rows were marked.")
    public ResponseEntity<MarkAllReadResponse> markAllRead(Authentication authentication) {
        return ResponseEntity.ok(new MarkAllReadResponse(service.markAllAsRead(authentication)));
    }

    @Schema(description = "The clear-all outcome")
    public record MarkAllReadResponse(
            @Schema(description = "How many unread rows were marked read", example = "7")
            long markedRead
    ) {
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

    /**
     * Phase 7 (execution plan §10 / §8.1 — notification routing): the
     * caller's effective HIERARCHICAL TOPIC matrix — every subject family
     * × the governed channels (EMAIL/WS) with the stored override or the
     * enabled default. The topic level is the type matrix's second
     * resolution level: the engine falls through to it only when the
     * type level has no explicit row.
     */
    @GetMapping("/notifications/preferences/topics")
    @Operation(summary = "Get my hierarchical topic notification preferences",
            description = "The caller's effective topic matrix — every notification topic "
                    + "× the EMAIL/WS channels with the stored override or the enabled "
                    + "default. Resolution order: type-level row > topic-level row > default.")
    public ResponseEntity<List<NotificationTopicPreferenceView>> getMyTopicPreferences(
            Authentication authentication) {
        return ResponseEntity.ok(topicPreferenceService.getMyTopicPreferences(authentication));
    }

    /**
     * Phase 7: applies the caller's topic switches (upsert) and returns
     * the resulting effective matrix. Channels outside EMAIL/WS are
     * rejected — the in-app channel is always on and push awaits the
     * provider decision (D-10).
     */
    @PutMapping("/notifications/preferences/topics")
    @Operation(summary = "Update my hierarchical topic notification preferences",
            description = "Applies the requested topic switches (upsert) and returns the "
                    + "resulting effective matrix. \"Back to default\" is enabled = true.")
    public ResponseEntity<List<NotificationTopicPreferenceView>> updateMyTopicPreferences(
            @Valid @RequestBody NotificationTopicPreferencesUpdateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                topicPreferenceService.updateMyTopicPreferences(authentication, request));
    }

    /**
     * Phase 7: the caller's own OPT-IN geographic subscriptions — the
     * optional widening of the routing engine's effective geo scope
     * beyond the caller's active membership (the documented default).
     */
    @GetMapping("/notifications/preferences/geo")
    @Operation(summary = "List my geographic notification subscriptions",
            description = "The caller's own opt-in level-3 neighborhood subscriptions. "
                    + "With none, the effective geo scope is the caller's active membership "
                    + "neighborhood — the documented default.")
    public ResponseEntity<List<NotificationGeoSubscriptionView>> getMyGeoSubscriptions(
            Authentication authentication) {
        return ResponseEntity.ok(geoSubscriptionService.getMySubscriptions(authentication));
    }

    /**
     * Phase 7: subscribes the caller to one level-3 neighborhood — the
     * explicit geographic act (the existence/level gate rides
     * GeoLookupPort; unknown 404, wider level 400). Idempotent on a live
     * duplicate.
     */
    @PostMapping("/notifications/preferences/geo")
    @Operation(summary = "Subscribe to a neighborhood's notifications",
            description = "Adds one opt-in level-3 neighborhood subscription for the caller "
                    + "(404 unknown location, 400 non-neighborhood level). Idempotent on a "
                    + "live duplicate.")
    public ResponseEntity<NotificationGeoSubscriptionView> subscribeToGeo(
            @Valid @RequestBody NotificationGeoSubscriptionRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(geoSubscriptionService.subscribe(authentication, request.locationId()));
    }

    /**
     * Phase 7: withdraws the caller's own subscription — the explicit
     * reversal (soft delete; the pair can be re-subscribed later). An
     * unknown pair is 404.
     */
    @DeleteMapping("/notifications/preferences/geo/{locationId}")
    @Operation(summary = "Withdraw a geographic notification subscription",
            description = "Soft-deletes the caller's own subscription for the given "
                    + "neighborhood (404 when none). The pair can be re-subscribed later.")
    public ResponseEntity<Void> withdrawGeoSubscription(@PathVariable UUID locationId,
                                                        Authentication authentication) {
        geoSubscriptionService.withdraw(authentication, locationId);
        return ResponseEntity.noContent().build();
    }

    /** Plan item 2.6: the badge shape (the MessagingController precedent). */
    @Schema(description = "Unread badge count")
    public record UnreadCountResponse(
            @Schema(description = "Unread notifications for the caller", example = "3")
            long unreadCount
    ) {
    }
}
