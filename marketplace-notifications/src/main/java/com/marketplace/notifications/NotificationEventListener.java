package com.marketplace.notifications;

import com.marketplace.shared.api.BookingConfirmedEvent;
import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ContentModeratedEvent;
import com.marketplace.shared.api.ContentReportResolvedEvent;
import com.marketplace.shared.api.DisputeOpenedEvent;
import com.marketplace.shared.api.DisputeResolvedEvent;
import com.marketplace.shared.api.EmailVerificationRequestedEvent;
import com.marketplace.shared.api.FollowedProviderNewListingEvent;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
import com.marketplace.shared.api.MembershipVerificationGrantedEvent;
import com.marketplace.shared.api.MessageReceivedEvent;
import com.marketplace.shared.api.NewListingInNeighborhoodEvent;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import com.marketplace.shared.api.PasswordResetRequestedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.PostCommentedEvent;
import com.marketplace.shared.api.PostReactedEvent;
import com.marketplace.shared.api.SavedSearchMatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;
    private final com.marketplace.notifications.routing.UrgentAlertNotificationRouter urgentAlertRouter;

    public NotificationEventListener(NotificationService notificationService,
                                     com.marketplace.notifications.routing.UrgentAlertNotificationRouter urgentAlertRouter) {
        this.notificationService = notificationService;
        this.urgentAlertRouter = urgentAlertRouter;
    }

    @ApplicationModuleListener
    public void onBookingCreated(BookingCreatedEvent event) {
        notificationService.onBookingCreated(event.bookingId());
        log.info("Notification sent for booking created: {}", event.bookingId());
    }

    /**
     * A-04 (official-compliance plan §6 wave A — A.1/A.2, the parallel
     * contracts ledger's additive registration): the security-mail pair.
     * Identity (Track A's garden) publishes each event inside the issuing
     * transaction; this consumer renders the mail through the house seam —
     * the mail channel ALONE, no in-app row, no WebSocket push, no
     * preference gate (the measured exception both NotificationService
     * methods document: the recipient is outside the application by
     * definition, and the OWASP-declared security mail rides no marketing
     * preference). The registry entry commits atomically with the token
     * row on the publisher's side, and a failed mail leg stays incomplete
     * for the framework's resubmission — the standing housekeeping.
     */
    @ApplicationModuleListener
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        notificationService.onPasswordResetRequested(
                event.userId(), event.displayName(), event.resetLink(), event.expiresAt());
        log.info("Password reset mail sent: userId={}", event.userId());
    }

    /**
     * A-04's A.2 leg — same contract as the reset listener above; the
     * welcome mail carries the one-time verification deep link that lifts
     * the registration hold (the dormant template's activation).
     */
    @ApplicationModuleListener
    public void onEmailVerificationRequested(EmailVerificationRequestedEvent event) {
        notificationService.onEmailVerificationRequested(
                event.userId(), event.displayName(), event.verificationLink());
        log.info("Email verification mail sent: userId={}", event.userId());
    }

    /**
     * A-03 (official-compliance plan 0.6 — the dead BookingConfirmedEvent's
     * delivery; the parallel contracts ledger recorded this consumer as
     * "returning with round A-03" and rules the crossing: booking (Track A)
     * publishes, the late-lander writes the listener here in notifications,
     * and the module's owner reviews it — execution plan §5.3). The event was
     * published at both confirm sites (the provider's confirm and the
     * payment-driven autoConfirm) with no consumer at all — which in the
     * official Modulith mechanism (reference/events.html) means the
     * Event Publication Registry never wrote an entry for it: the registry
     * "finds out about the transactional event listeners that will get the
     * event delivered and writes entries for each of them", so a
     * listener-less event is a fire into the void — no registry row, no
     * completion tracking, no retry. This listener completes the journey:
     * from here the publication rides the registry like every other house
     * event (AFTER_COMMIT, its own transaction, framework-managed
     * completion/resubmission).
     */
    @ApplicationModuleListener
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        notificationService.onBookingConfirmed(event.bookingId());
        log.info("Notification sent for booking confirmed: {}", event.bookingId());
    }

    /**
     * A-11 (official-compliance plan §6 wave C — C.1: the order machine's
     * notification legs; the parallel contracts ledger records this
     * late-lander crossing — orders (Track A) publishes, the late-lander
     * writes the listener here in notifications, and the module's owner
     * reviews it, execution plan §5.3). The payload carries the consumer
     * id, so the listener resolves nothing — the same AFTER_COMMIT /
     * independent-transaction contract every listener here rides
     * (reference/events.html).
     */
    @ApplicationModuleListener
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        notificationService.onOrderConfirmed(event.orderId(), event.consumerId());
        log.info("Notification sent for order confirmed: {}", event.orderId());
    }

    @ApplicationModuleListener
    public void onOrderFulfilled(OrderFulfilledEvent event) {
        notificationService.onOrderFulfilled(event.orderId(), event.consumerId());
        log.info("Notification sent for order fulfilled: {}", event.orderId());
    }

    @ApplicationModuleListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        notificationService.onOrderCancelled(event.orderId(), event.consumerId(), event.reason());
        log.info("Notification sent for order cancelled: {}", event.orderId());
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        notificationService.onPaymentStateChanged(event.paymentIntentId(), event.state());
        log.info("Notification sent for payment state change: intentId={}, state={}",
                event.paymentIntentId(), event.state());
    }

    /**
     * L34 (realestate systems plan §5 — lead capture): the provider's
     * LEAD_RECEIVED alert. Same contract as the two listeners above — after
     * commit, its own transaction, the framework's retry: a failed delivery
     * never loses the lead (the registry entry stays incomplete until the
     * listener succeeds).
     */
    @ApplicationModuleListener
    public void onListingLeadCreated(ListingLeadCreatedEvent event) {
        notificationService.onLeadReceived(event.leadId(), event.listingId(), event.providerId());
        log.info("Notification sent for listing lead: leadId={}, listingId={}",
                event.leadId(), event.listingId());
    }

    /**
     * L35 (realestate systems plan §5 — saved searches and alerts): the
     * SAVED_SEARCH_MATCH alert. The event is ALREADY aggregated per user
     * (the matcher's structural aggregation — one event per user per
     * listing carrying the matched saved-search ids), so one event is one
     * notification regardless of how many searches matched. Same contract
     * as the listeners above — after commit, its own transaction, the
     * framework's retry: a failed delivery never loses the match.
     */
    @ApplicationModuleListener
    public void onSavedSearchMatched(SavedSearchMatchedEvent event) {
        notificationService.onSavedSearchMatch(event.userId(), event.listingId(),
                event.savedSearchIds().size());
        log.info("Notification sent for saved-search match: userId={}, listingId={}, searches={}",
                event.userId(), event.listingId(), event.savedSearchIds().size());
    }

    /**
     * L42 (neighborhood community plan §5 — the posts/feed/comments layer):
     * the post author's POST_COMMENTED alert. Same contract as the
     * listeners above — after commit, its own transaction, the
     * framework's retry: a failed delivery never loses the comment's
     * notification (the registry entry stays incomplete until the
     * listener succeeds).
     *
     * <p><b>The self-comment skip lives HERE</b> (the plan's criterion 4:
     * "ما لم يكن المعلق هو المؤلف"): the event is published for every
     * comment — the fact stays honest and auditable in the publication
     * registry — and the listener compares the two ids it carries before
     * notifying. The author commenting on their own post is the one
     * delivery this module deliberately drops.
     */
    @ApplicationModuleListener
    public void onPostCommented(PostCommentedEvent event) {
        if (event.commentAuthorId().equals(event.postAuthorId())) {
            log.debug("Self-comment on post {} — no POST_COMMENTED notification by policy",
                    event.postId());
            return;
        }
        notificationService.onPostCommented(event.postId(), event.postAuthorId());
        log.info("Notification sent for post comment: postId={}, author={}",
                event.postId(), event.postAuthorId());
    }

    /**
     * L46 (neighborhood community plan §5 — the community realestate
     * bridge): the neighborhood member's NEW_LISTING_IN_NEIGHBORHOOD
     * alert. The event arrives pre-scoped per member (the community
     * bridge's structural one-event-per-recipient fan-out), so one event
     * is one notification. Same contract as the listeners above — after
     * commit, its own transaction, the framework's retry: a failed
     * delivery never loses the match (the registry entry stays
     * incomplete until the listener succeeds).
     *
     * <p><b>The publisher's own exclusion lives on the community side</b>
     * (the plan's criterion 4: the bridge listener skips the
     * publisher-member before publishing) — every event this listener
     * receives is a genuine neighbor alert, so this method delivers
     * unconditionally, keeping the delivery contract one shape for every
     * caller.
     */
    @ApplicationModuleListener
    public void onNewListingInNeighborhood(NewListingInNeighborhoodEvent event) {
        notificationService.onNewListingInNeighborhood(event.recipientId(), event.listingId());
        log.info("Notification sent for new neighborhood listing: recipient={}, listing={}",
                event.recipientId(), event.listingId());
    }

    /**
     * L45 (neighborhood community plan §5 — the moderation &amp; reports
     * layer): the moderated content's author alert. Same contract as the
     * listeners above — after commit, its own transaction, the
     * framework's retry: a failed delivery never loses the moderation
     * alert (the registry entry stays incomplete until the listener
     * succeeds), and the hide itself already committed atomically with
     * the report's RESOLVED close.
     *
     * <p><b>The one-real-hide-one-alert policy lives on the COMMUNITY
     * side</b> (the resolve command fires the event on the real
     * VISIBLE→HIDDEN transition alone — an already-hidden or
     * author-deleted target carries no new fact for the author) — every
     * event this listener receives is a genuine first hide, so this
     * method delivers unconditionally, keeping the delivery contract one
     * shape for every caller.
     */
    @ApplicationModuleListener
    public void onContentModerated(ContentModeratedEvent event) {
        notificationService.onContentModerated(
                event.recipientId(), event.targetType(), event.targetId());
        log.info("Notification sent for content moderation: recipient={}, targetType={}, target={}",
                event.recipientId(), event.targetType(), event.targetId());
    }

    /**
     * L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions
     * layer): the post author's POST_REACTED alert. Same contract as the
     * listeners above — after commit, its own transaction, the
     * framework's retry: a failed delivery never loses the thank's
     * notification (the registry entry stays incomplete until the
     * listener succeeds).
     *
     * <p><b>The self-thank skip lives HERE</b> (the
     * {@code PostCommentedEvent} criterion-4 precedent verbatim): the
     * event is published for every reaction — the fact stays honest and
     * auditable in the publication registry — and the listener compares
     * the two ids it carries before notifying. The author thanking their
     * own post is the one delivery this module deliberately drops.
     */
    @ApplicationModuleListener
    public void onPostReacted(PostReactedEvent event) {
        if (event.reactorId().equals(event.postAuthorId())) {
            log.debug("Self-thank on post {} — no POST_REACTED notification by policy",
                    event.postId());
            return;
        }
        notificationService.onPostReacted(event.postId(), event.postAuthorId());
        log.info("Notification sent for post reaction: postId={}, author={}",
                event.postId(), event.postAuthorId());
    }

    /**
     * W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave,
     * G21): the follower's FOLLOWED_PROVIDER_NEW_LISTING alert. The event
     * arrives pre-scoped per follower (the identity side's follow bridge
     * + its alert ledger — the structural one-event-per-recipient fan-out
     * the {@code NewListingInNeighborhoodEvent} precedent documents), so
     * one event is one notification. Same contract as the listeners above
     * — after commit, its own transaction, the framework's retry: a failed
     * delivery never loses the announcement alert for that follower (the
     * registry entry stays incomplete until the listener succeeds).
     */
    @ApplicationModuleListener
    public void onFollowedProviderNewListing(FollowedProviderNewListingEvent event) {
        notificationService.onFollowedProviderNewListing(event.recipientId(), event.listingId());
        log.info("Notification sent for followed-provider listing: recipient={}, listing={}",
                event.recipientId(), event.listingId());
    }


    /**
     * B-08 (compliance plan 0.10 — the CR-4 wiring, completing the arrival
     * chain end to end): the recipient's MESSAGE_RECEIVED alert. The event
     * record lives in shared/api (the house convention for cross-boundary
     * events, measured on every record this listener already consumes) —
     * the notifications pom carries no messaging dependency, so the shared
     * placement needs no pom change anywhere. Same contract as the
     * listeners above — after commit, its own transaction, the framework's
     * retry: a failed delivery never loses the arrival notification.
     */
    @ApplicationModuleListener
    public void onMessageReceived(MessageReceivedEvent event) {
        notificationService.onMessageReceived(event.conversationId(), event.recipientId());
        log.info("Notification sent for message received: messageId={}, conversationId={}",
                event.messageId(), event.conversationId());
    }

    /**
     * B-17 (compliance plan C.9 — the CodeRabbit round-1 adoption landing
     * the CR-10 crossing): the member's MEMBERSHIP_VERIFIED notification —
     * the grant verdict's own journey's arrival point (the platform
     * identity §0.1 rule: a notification for every event; an event without
     * a listener is a measured defect). The event record lives in
     * shared/api (the CR-4 placement, the MessageReceivedEvent flow
     * verbatim — no pom change anywhere) and is published by
     * {@code NeighborhoodMembershipService.reviewVerification} on the
     * approve direction inside the reviewer's own transaction. Same
     * contract as the listeners above — after commit, its own transaction,
     * the framework's retry: a failed delivery never loses the verified
     * member's notification (the registry entry stays incomplete until
     * the listener succeeds). The delivery itself is unconditional — the
     * recipient arrives resolved at the source (the event carries the
     * complete trust fact), keeping the delivery contract one shape for
     * every caller.
     */
    @ApplicationModuleListener
    public void onMembershipVerificationGranted(MembershipVerificationGrantedEvent event) {
        notificationService.onVerificationGranted(event.userId(), event.locationId());
        log.info("Notification sent for membership verification granted: membershipId={}, userId={}, "
                        + "locationId={}", event.membershipId(), event.userId(), event.locationId());
    }

    /**
     * B-17 (compliance plan C.9 — the CodeRabbit round-1 adoption landing
     * the CR-10 crossing): the REPORTER's REPORT_RESOLVED notification —
     * every resolve outcome is the reporter's journey's arrival
     * ({@code RESOLVED} behind {@code HIDE_CONTENT} and {@code DISMISSED}
     * behind {@code DISMISS} alike; distinct from the author's
     * CONTENT_MODERATED alert, which is the hide fact alone). Both
     * publication paths fire it — the human
     * {@code ContentReportService.resolveReport} and the automatic
     * {@code ModerationRuleEngine} — and the event record lives in
     * shared/api (the CR-4 placement, the MessageReceivedEvent flow
     * verbatim — no pom change anywhere). Same contract as the listeners
     * above — after commit, its own transaction, the framework's retry: a
     * failed delivery never loses the reporter's adjudication
     * notification (the registry entry stays incomplete until the
     * listener succeeds).
     */
    @ApplicationModuleListener
    public void onContentReportResolved(ContentReportResolvedEvent event) {
        notificationService.onReportResolved(
                event.reporterId(), event.targetType(), event.targetId(), event.outcome());
        log.info("Notification sent for content report resolved: reportId={}, reporterId={}, "
                        + "outcome={}", event.reportId(), event.reporterId(), event.outcome());
    }

    /**
     * Task 5-f (the discovery waves' measured repairs — the
     * events-without-consumers closure, the B-06 dispute pair's delivery):
     * the dispute opener's DISPUTE_OPENED acknowledgment. The B-06 events
     * were published since their landing with ZERO listeners (the measured
     * defect — the identity rule "an event without a listener is a
     * measured defect", the BookingConfirmedEvent A-03 precedent) and the
     * records now live in shared/api (the contracts ledger §1.1 own
     * ruling: the record moves to shared/api at the first cross-boundary
     * consumer — this listener is that consumer, the late-lander crossing
     * documented in the worklog).
     *
     * <p><b>The recipient is the dispute's OPENER — the honest
     * acknowledgment, not a counterpart alert:</b> the payload carries
     * {@code openedBy} alone (no counterpart party fact), so the
     * delivery is the opener's own pipeline-entry confirmation ("تم فتح
     * نزاعك رقم ... قيد المراجعة"). Same contract as the listeners above
     * — after commit, its own transaction, the framework's retry: a
     * failed delivery never loses the acknowledgment (the registry entry
     * stays incomplete until the listener succeeds) — and the delivery is
     * IDEMPOTENT beyond that: the service dedupes on
     * {@code source_event_id = disputeId} (the notifications ledger, V180)
     * so a re-delivered publication can never duplicate the row.
     */
    @ApplicationModuleListener
    public void onDisputeOpened(DisputeOpenedEvent event) {
        notificationService.onDisputeOpened(event.disputeId(), event.openedBy());
        log.info("Notification sent for dispute opened: disputeId={}, openedBy={}",
                event.disputeId(), event.openedBy());
    }

    /**
     * Task 5-f: the dispute opener's DISPUTE_RESOLVED adjudication fact —
     * the resolve decision's arrival with its EXECUTED financial outcome
     * ({@code resolution} rides the stored name, {@code
     * refundedAmountCents} the executed movement — the complete-fact
     * discipline; {@code openedBy} joined the payload AT the shared/api
     * relocation, the {@code MessageReceivedEvent} complete-fact rule —
     * no consumer re-derives party facts). Same contract as the listener
     * above — after commit, its own transaction, the framework's retry —
     * and idempotent on the DETERMINISTIC ledger key
     * {@code nameUUIDFromBytes(disputeId + "-resolved")}: the redelivered
     * publication derives the same source_event_id, the ledger holds
     * exactly-once by construction.
     */
    @ApplicationModuleListener
    public void onDisputeResolved(DisputeResolvedEvent event) {
        notificationService.onDisputeResolved(event.disputeId(), event.openedBy(),
                event.resolution(), event.refundedAmountCents());
        log.info("Notification sent for dispute resolved: disputeId={}, openedBy={}, resolution={}",
                event.disputeId(), event.openedBy(), event.resolution());
    }

    /**
     * Phase 7 (execution plan §10 / §8.1 — notification routing): the
     * official-urgent-alert's notification leg — the {@code URGENT_ALERT}
     * type's V180 registration finally delivering. The listener is the
     * Modulith seam only (AFTER_COMMIT, its own transaction, the
     * framework's retry — the house contract every listener here rides);
     * the ROUTING is the {@code UrgentAlertNotificationRouter}'s
     * documented official-alert policy: validity re-checked through the
     * standing {@code UrgentAlertsPort}, geography through the scope's
     * two data owners (the members port + the V198 opt-in
     * subscriptions), the channels through the §8.1 routing engine with
     * the per-channel idempotency ledger — a retried publication can
     * never duplicate a delivery (the Phase 7 gate).
     *
     * <p><b>The recipients are NOT derived from unrelated payloads:</b>
     * the event carries the committed alert facts (source, scope, level,
     * title) and the fan-out resolves the SCOPE's own membership through
     * the data-owner port — the §8.1 rule "لا يشتق المستلمون في مستمع
     * الإشعارات إذا كان الحدث يحمل الحقيقة الملتزمة" governs party facts
     * the event was supposed to carry (the DisputeResolvedEvent
     * {@code openedBy} rule), not the enumeration of a geo scope's own
     * members, which is the fan-out's input by construction (the
     * NeighborhoodMembersPort contract documents the split).
     */
    @ApplicationModuleListener
    public void onUrgentAlertPublished(
            com.marketplace.shared.api.UrgentAlertPublishedEvent event) {
        urgentAlertRouter.onUrgentAlertPublished(event);
        log.info("Urgent alert notification leg completed: alertId={}, scope={}",
                event.alertId(), event.locationId());
    }
}
