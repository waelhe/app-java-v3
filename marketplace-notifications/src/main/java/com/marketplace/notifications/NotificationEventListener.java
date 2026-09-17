package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.PostCommentedEvent;
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

}
