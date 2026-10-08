package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ContentModeratedEvent;
import com.marketplace.shared.api.ContentReportResolvedEvent;
import com.marketplace.shared.api.FollowedProviderNewListingEvent;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
import com.marketplace.shared.api.MembershipVerificationGrantedEvent;
import com.marketplace.shared.api.MessageReceivedEvent;
import com.marketplace.shared.api.NewListingInNeighborhoodEvent;
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

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    public void onBookingCreated(BookingCreatedEvent event) {
        notificationService.onBookingCreated(event.bookingId());
        log.info("Notification sent for booking created: {}", event.bookingId());
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
}
