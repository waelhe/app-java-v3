package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final PaymentIntentLookupPort paymentIntentLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final EmailNotificationService emailNotificationService;
    private final Optional<SimpMessagingTemplate> messagingTemplate;
    private final NotificationPreferenceService preferences;
    private final NotificationTextSource text;

    public NotificationService(NotificationRepository repository,
                               BookingParticipantProvider bookingParticipantProvider,
                               PaymentIntentLookupPort paymentIntentLookupPort,
                               CurrentUserProvider currentUserProvider,
                               EmailNotificationService emailNotificationService,
                               Optional<SimpMessagingTemplate> messagingTemplate,
                               NotificationPreferenceService preferences,
                               NotificationTextSource text) {
        this.repository = repository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.emailNotificationService = emailNotificationService;
        this.messagingTemplate = messagingTemplate;
        this.preferences = preferences;
        this.text = text;
    }

    public void onBookingCreated(UUID bookingId) {
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        // B-11 (compliance plan B.6): every composed text rides the
        // module's MessageSource channel at the platform's standard
        // locale — the byte-identical English floor stays the default
        // bundle's own literals. The L22 channel discipline is unchanged.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String consumerMessage = text.compose("notification.BOOKING_CREATED.consumer", platform, bookingId);
        String providerMessage = text.compose("notification.BOOKING_CREATED.provider", platform, bookingId);
        // L22: the in-app channel is always on — the row lands for both
        // participants regardless of preferences (roadmap: "inside the app
        // always").
        repository.save(Notification.create(info.consumerId(), NotificationType.BOOKING_CREATED.name(), consumerMessage));
        repository.save(Notification.create(info.providerId(), NotificationType.BOOKING_CREATED.name(), providerMessage));
        if (preferences.isChannelEnabled(info.consumerId(), NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(info.consumerId(),
                    text.compose("email.BOOKING_CREATED.consumer.subject", platform),
                    "email/notification",
                    Map.of("message", text.compose("email.BOOKING_CREATED.consumer.body", platform, bookingId)));
        }
        if (preferences.isChannelEnabled(info.providerId(), NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(info.providerId(),
                    text.compose("email.BOOKING_CREATED.provider.subject", platform),
                    "email/notification",
                    Map.of("message", text.compose("email.BOOKING_CREATED.provider.body", platform, bookingId)));
        }
        sendWebSocket(info.consumerId(), NotificationType.BOOKING_CREATED, consumerMessage);
        sendWebSocket(info.providerId(), NotificationType.BOOKING_CREATED, providerMessage);
    }

    public void onBookingConfirmed(UUID bookingId) {
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        // A-03 (compliance plan 0.6 — the dead BookingConfirmedEvent's
        // delivery, the notification leg of the owner's identity rule "an
        // event without a listener is a measured defect"): the recipient is
        // the CONSUMER alone — the party whose booking just moved to
        // CONFIRMED and who is waiting on that answer. The provider's
        // knowledge of the same moment already arrives on its own channel:
        // on the manual path the provider performed the confirm themselves,
        // and on the payment-driven autoConfirm path both parties already
        // receive the PAYMENT_STATE notification for the state change that
        // triggered it — a second provider row here would duplicate that
        // alert, not carry a new fact. Same delivery shape as
        // onBookingCreated: the in-app row is always on, EMAIL rides the
        // per-type/channel preference matrix (L22) from day one, WS honors
        // an explicit opt-out.
        repository.save(Notification.create(info.consumerId(),
                NotificationType.BOOKING_CONFIRMED.name(), "Booking confirmed: " + bookingId));
        if (preferences.isChannelEnabled(info.consumerId(), NotificationType.BOOKING_CONFIRMED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(info.consumerId(), "Booking Confirmed", "email/notification",
                    Map.of("message", "Your booking " + bookingId + " has been confirmed."));
        }
        sendWebSocket(info.consumerId(), NotificationType.BOOKING_CONFIRMED, "Booking confirmed: " + bookingId);
    }

    public void onPaymentStateChanged(UUID paymentIntentId, String state) {
        paymentIntentLookupPort.findById(paymentIntentId).ifPresent(intent -> {
            // B-11: the state name renders through the payments vocabulary
            // (unknown states ride through raw — the honest degradation of
            // the pre-B-11 concatenation).
            Locale platform = NotificationTextSource.PLATFORM_LOCALE;
            String stateWord = text.paymentStateWord(state, platform);
            // W5 (yelp-level plan §5 — G24): the ad bill's own shape — the
            // payer is the provider ALONE (no booking, no second party): one
            // notification, the campaign names the bill (the booking path's
            // message would name a booking that does not exist). The same
            // PAYMENT_STATE type rides both origins — it IS a payment state
            // change either way (no new enum value, no preferences-CHECK
            // widening pair).
            if (intent.isAdOrigin()) {
                String adMessage = text.compose("notification.PAYMENT_STATE.ad", platform, stateWord, intent.adCampaignId());
                repository.save(Notification.create(intent.consumerId(),
                        NotificationType.PAYMENT_STATE.name(), adMessage));
                if (preferences.isChannelEnabled(intent.consumerId(),
                        NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)) {
                    emailNotificationService.sendEmail(intent.consumerId(),
                            text.compose("email.PAYMENT_STATE.subject", platform, stateWord),
                            "email/notification", Map.of("message", adMessage));
                }
                sendWebSocket(intent.consumerId(), NotificationType.PAYMENT_STATE, adMessage);
                return;
            }
            BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
            String message = text.compose("notification.PAYMENT_STATE.booking", platform, stateWord, intent.bookingId());
            // L22: the in-app channel is always on (see onBookingCreated).
            repository.save(Notification.create(intent.consumerId(), NotificationType.PAYMENT_STATE.name(), message));
            repository.save(Notification.create(bookingInfo.providerId(), NotificationType.PAYMENT_STATE.name(), message));
            if (preferences.isChannelEnabled(intent.consumerId(), NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)) {
                emailNotificationService.sendEmail(intent.consumerId(),
                        text.compose("email.PAYMENT_STATE.subject", platform, stateWord),
                        "email/notification", Map.of("message", message));
            }
            if (preferences.isChannelEnabled(bookingInfo.providerId(), NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)) {
                emailNotificationService.sendEmail(bookingInfo.providerId(),
                        text.compose("email.PAYMENT_STATE.subject", platform, stateWord),
                        "email/notification", Map.of("message", message));
            }
            sendWebSocket(intent.consumerId(), NotificationType.PAYMENT_STATE, message);
            sendWebSocket(bookingInfo.providerId(), NotificationType.PAYMENT_STATE, message);
        });
    }

    /**
     * L34 (realestate systems plan §5 — lead capture): the provider's
     * LEAD_RECEIVED alert — the same delivery shape as the two event
     * points above (in-app row always lands; WebSocket and email ride
     * their L22 per-type/channel preferences).
     *
     * <p><b>The recipient is the listing's provider id — which lives in
     * the users.id space (the A1/V2 measured fact:
     * {@code provider_listings.provider_id references users(id)}, the
     * same seam {@code onBookingCreated} uses for its BookingInfo
     * provider): the id IS the recipient, no profile resolution, no
     * unlinked-profile edge to skip.
     */
    public void onLeadReceived(UUID leadId, UUID listingId, UUID providerUserId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale (the pre-B-11 English literal is the
        // default bundle's own entry).
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.LEAD_RECEIVED", platform, listingId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(providerUserId,
                NotificationType.LEAD_RECEIVED.name(), message));
        if (preferences.isChannelEnabled(providerUserId,
                NotificationType.LEAD_RECEIVED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(providerUserId,
                    text.compose("email.LEAD_RECEIVED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(providerUserId, NotificationType.LEAD_RECEIVED, message);
    }

    /**
     * L35 (realestate systems plan §5 — saved searches and alerts): the
     * SAVED_SEARCH_MATCH alert — the same delivery shape as the event
     * points above (in-app row always lands; WebSocket and email ride
     * their L22 per-type/channel preferences). The event arrives
     * pre-aggregated (the matcher's structural aggregation — one event
     * per user per listing), so the count reads "N of your saved
     * searches" without any de-duplication here.
     */
    public void onSavedSearchMatch(UUID userId, UUID listingId, int savedSearchCount) {
        // B-11: the singular/plural pair rides the bundle — the count names
        // the searches the same way the pre-B-11 branch did, at the platform
        // locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = savedSearchCount == 1
                ? text.compose("notification.SAVED_SEARCH_MATCH.singular", platform, listingId)
                : text.compose("notification.SAVED_SEARCH_MATCH.plural", platform, savedSearchCount, listingId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(userId,
                NotificationType.SAVED_SEARCH_MATCH.name(), message));
        if (preferences.isChannelEnabled(userId,
                NotificationType.SAVED_SEARCH_MATCH, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(userId,
                    text.compose("email.SAVED_SEARCH_MATCH.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(userId, NotificationType.SAVED_SEARCH_MATCH, message);
    }

    /**
     * L42 (neighborhood community plan §5 — the posts/feed/comments
     * layer): the post author's POST_COMMENTED alert — the same delivery
     * shape as the event points above (in-app row always lands; WebSocket
     * and email ride their L22 per-type/channel preferences). The
     * recipient is the post's author id, which lives in the users.id
     * space — the id IS the recipient, the same seam
     * {@code onLeadReceived} uses for its provider.
     *
     * <p>The self-comment skip is the LISTENER's own policy (the plan's
     * criterion 4: "ما لم يكن المعلق هو المؤلف") — this method delivers
     * unconditionally, so the delivery contract stays one shape for
     * every caller.
     */
    public void onPostCommented(UUID postId, UUID postAuthorId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.POST_COMMENTED", platform, postId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(postAuthorId,
                NotificationType.POST_COMMENTED.name(), message));
        if (preferences.isChannelEnabled(postAuthorId,
                NotificationType.POST_COMMENTED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(postAuthorId,
                    text.compose("email.POST_COMMENTED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(postAuthorId, NotificationType.POST_COMMENTED, message);
    }

    /**
     * L46 (neighborhood community plan §5 — the community realestate
     * bridge): the neighborhood member's NEW_LISTING_IN_NEIGHBORHOOD
     * alert — the same delivery shape as the event points above (in-app
     * row always lands; WebSocket and email ride their L22 per-type/
     * channel preferences). The recipient is the membership's user id,
     * which lives in the users.id space — the id IS the recipient, the
     * same seam {@code onSavedSearchMatch} uses.
     *
     * <p>The publisher's own exclusion is the COMMUNITY side's bridge
     * policy (the plan's criterion 4) — this method delivers
     * unconditionally, so the delivery contract stays one shape for
     * every caller.
     */
    public void onNewListingInNeighborhood(UUID recipientId, UUID listingId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.NEW_LISTING_IN_NEIGHBORHOOD", platform, listingId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(recipientId,
                NotificationType.NEW_LISTING_IN_NEIGHBORHOOD.name(), message));
        if (preferences.isChannelEnabled(recipientId,
                NotificationType.NEW_LISTING_IN_NEIGHBORHOOD, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipientId,
                    text.compose("email.NEW_LISTING_IN_NEIGHBORHOOD.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(recipientId, NotificationType.NEW_LISTING_IN_NEIGHBORHOOD, message);
    }

    /**
     * L45 (neighborhood community plan §5 — the moderation &amp; reports
     * layer): the moderated content's author alert — the same delivery
     * shape as the event points above (in-app row always lands; WebSocket
     * and email ride their L22 per-type/channel preferences). The
     * recipient is the content's author in the users.id space — the id
     * IS the recipient, the same seam {@code onPostCommented} uses.
     *
     * <p>{@code targetType} arrives as the community domain's stored name
     * ("POST"/"COMMENT" — the ContentModeratedEvent vocabulary, the
     * PaymentStateChangedEvent String precedent) and rides the message
     * so the alert reads as the fact it is; the one-real-hide-one-alert
     * policy lives on the COMMUNITY side's resolve command — this method
     * delivers unconditionally, so the delivery contract stays one shape
     * for every caller.
     */
    public void onContentModerated(UUID recipientId, String targetType, UUID targetId) {
        // B-11: the community vocabulary word renders at the platform
        // locale (unknown names ride through raw); the pre-B-11
        // lowercased-English rendering is the default bundle's own entry.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.CONTENT_MODERATED", platform,
                text.targetTypeWord(targetType, platform), targetId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(recipientId,
                NotificationType.CONTENT_MODERATED.name(), message));
        if (preferences.isChannelEnabled(recipientId,
                NotificationType.CONTENT_MODERATED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipientId,
                    text.compose("email.CONTENT_MODERATED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(recipientId, NotificationType.CONTENT_MODERATED, message);
    }

    /**
     * L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions
     * layer): the thanked post's author's POST_REACTED alert — the same
     * delivery shape as the event points above (in-app row always lands;
     * WebSocket and email ride their L22 per-type/channel preferences).
     * The recipient is the post's author id, which lives in the
     * users.id space — the id IS the recipient, the same seam
     * {@code onPostCommented} uses.
     *
     * <p>The self-thank skip is the LISTENER's own policy (the
     * {@code PostCommentedEvent} criterion-4 precedent) — this method
     * delivers unconditionally, so the delivery contract stays one shape
     * for every caller.
     */
    public void onPostReacted(UUID postId, UUID postAuthorId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.POST_REACTED", platform, postId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(postAuthorId,
                NotificationType.POST_REACTED.name(), message));
        if (preferences.isChannelEnabled(postAuthorId,
                NotificationType.POST_REACTED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(postAuthorId,
                    text.compose("email.POST_REACTED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(postAuthorId, NotificationType.POST_REACTED, message);
    }

    /**
     * W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave,
     * G21): the follower's FOLLOWED_PROVIDER_NEW_LISTING alert — the same
     * delivery shape as the event points above (in-app row always lands;
     * WebSocket and email ride their L22 per-type/channel preferences).
     * The recipient is the follower's user id, which lives in the
     * users.id space — the id IS the recipient, the same seam
     * {@code onNewListingInNeighborhood} uses.
     *
     * <p>The event arrives pre-scoped per follower (the identity side's
     * follow bridge + its alert ledger — the structural exactly-one per
     * (follower, listing) pair), so one event is one notification; the
     * bridge's deduplication is upstream, never here (the
     * {@code onSavedSearchMatch} aggregation contract's own division of
     * labor).
     */
    public void onFollowedProviderNewListing(UUID recipientId, UUID listingId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.FOLLOWED_PROVIDER_NEW_LISTING", platform, listingId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(recipientId,
                NotificationType.FOLLOWED_PROVIDER_NEW_LISTING.name(), message));
        if (preferences.isChannelEnabled(recipientId,
                NotificationType.FOLLOWED_PROVIDER_NEW_LISTING, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipientId,
                    text.compose("email.FOLLOWED_PROVIDER_NEW_LISTING.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(recipientId, NotificationType.FOLLOWED_PROVIDER_NEW_LISTING, message);
    }

    /**
     * A-04 (official-compliance plan §6 wave A — A.1): the password-reset
     * mail leg — the DORMANT {@code email/password-reset} template's
     * activation, with exactly the model variables it was authored with
     * ({@code name}, {@code resetLink}, {@code expirationMinutes}).
     *
     * <p><b>Why this leg carries NO in-app row, NO WebSocket push, and NO
     * preference gate — a measured exception to every channel rule above,
     * stated here so the exception is policy, not omission:</b> the
     * recipient is by definition OUTSIDE the application (the reset
     * requester forgot the very credential a session would need), so the
     * in-app channels have no reader to reach; and the OWASP Forgot
     * Password Cheat Sheet (the declared trusted community source) treats
     * this as security mail, not a notification preference — an account's
     * redemption right is delivered regardless of marketing-channel
     * opt-outs. The NotificationType vocabulary therefore gains nothing
     * (no type, no preferences-CHECK widening pair): this is the mail
     * channel alone, the same {@link EmailNotificationService} seam every
     * other mail rides.</p>
     */
    public void onPasswordResetRequested(UUID userId, String displayName,
                                         String resetLink, java.time.Instant expiresAt) {
        long expirationMinutes = Math.max(0,
                java.time.Duration.between(java.time.Instant.now(), expiresAt).toMinutes());
        emailNotificationService.sendEmail(userId, "Reset Your Password", "email/password-reset",
                Map.of("name", displayName == null ? "" : displayName,
                        "resetLink", resetLink,
                        "expirationMinutes", expirationMinutes));
    }

    /**
     * A-04 (wave A — A.2): the email-verification mail leg — the DORMANT
     * {@code email/welcome} template's activation for its real purpose:
     * the welcome mail now carries the one-time verification deep link
     * that lifts the registration hold. The same measured exception as
     * {@link #onPasswordResetRequested}: the unverified account's holder
     * cannot be inside the application (the hold locks the login gate), so
     * no in-app channel and no preference gate — the mail channel alone.
     */
    public void onEmailVerificationRequested(UUID userId, String displayName, String verificationLink) {
        emailNotificationService.sendEmail(userId, "Welcome to Marketplace — verify your email",
                "email/welcome",
                Map.of("name", displayName == null ? "" : displayName,
                        "verificationLink", verificationLink));
    }

    /**
     * A-11 (official-compliance plan §6 wave C — C.1: the order machine's
     * CONFIRMED leg). The recipient is the order's CONSUMER alone — the
     * party waiting on the merchant's acceptance. The same delivery shape
     * as onBookingConfirmed: the in-app row is always on, EMAIL rides the
     * per-type/channel preference matrix (L22) from day one, WS honors an
     * explicit opt-out. The event payload carries the consumer id, so
     * unlike the booking legs this path never consults the participant
     * provider — the payload IS the resolution (the ledger's placement
     * rule paying off).
     */
    public void onOrderConfirmed(UUID orderId, UUID consumerId) {
        String message = "Order confirmed: " + orderId;
        repository.save(Notification.create(consumerId,
                NotificationType.ORDER_CONFIRMED.name(), message));
        if (preferences.isChannelEnabled(consumerId, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(consumerId, "Order Confirmed", "email/notification",
                    Map.of("message", "Your order " + orderId + " has been confirmed."));
        }
        sendWebSocket(consumerId, NotificationType.ORDER_CONFIRMED, message);
    }

    /**
     * A-11 (C.1: the order machine's FULFILLED leg — the delivery
     * completion). Recipient and channels as onOrderConfirmed.
     */
    public void onOrderFulfilled(UUID orderId, UUID consumerId) {
        String message = "Order fulfilled: " + orderId;
        repository.save(Notification.create(consumerId,
                NotificationType.ORDER_FULFILLED.name(), message));
        if (preferences.isChannelEnabled(consumerId, NotificationType.ORDER_FULFILLED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(consumerId, "Order Fulfilled", "email/notification",
                    Map.of("message", "Your order " + orderId + " has been fulfilled."));
        }
        sendWebSocket(consumerId, NotificationType.ORDER_FULFILLED, message);
    }

    /**
     * A-11 (C.1: the order machine's CANCELLED leg). The reason rides the
     * event payload so the buyer learns WHY without any cross-module
     * re-query.
     */
    public void onOrderCancelled(UUID orderId, UUID consumerId, String reason) {
        String message = "Order cancelled: " + orderId + (reason == null || reason.isBlank() ? "" : " — " + reason);
        repository.save(Notification.create(consumerId,
                NotificationType.ORDER_CANCELLED.name(), message));
        if (preferences.isChannelEnabled(consumerId, NotificationType.ORDER_CANCELLED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(consumerId, "Order Cancelled", "email/notification",
                    Map.of("message", "Your order " + orderId + " was cancelled"
                            + (reason == null || reason.isBlank() ? "." : ": " + reason)));
        }
        sendWebSocket(consumerId, NotificationType.ORDER_CANCELLED, message);
    }

    private void sendWebSocket(UUID userId, NotificationType type, String message) {
        // L22: WS sends by default and honors an explicit opt-out — the
        // preference check is the single gate before the push.
        if (!preferences.isChannelEnabled(userId, type, NotificationChannel.WS)) {
            return;
        }
        messagingTemplate.ifPresent(template ->
            template.convertAndSend("/topic/notifications/" + userId,
                    new WebSocketNotification(type.name(), message))
        );
    }

    /**
     * B-08 (compliance plan 0.10 — the §3.4-8 defect): the arrival
     * notification for the conversation's OTHER participant — the same
     * delivery shape as the event points above (in-app row always lands;
     * WebSocket and email ride their L22 per-type/channel preferences).
     * The recipient arrives resolved at the source (the messaging
     * publisher holds the conversation) — this method delivers
     * unconditionally, so the delivery contract stays one shape for every
     * caller (the onPostCommented criterion-4 precedent).
     */
    public void onMessageReceived(UUID conversationId, UUID recipientId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.MESSAGE_RECEIVED", platform, conversationId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(recipientId,
                NotificationType.MESSAGE_RECEIVED.name(), message));
        if (preferences.isChannelEnabled(recipientId,
                NotificationType.MESSAGE_RECEIVED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipientId,
                    text.compose("email.MESSAGE_RECEIVED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(recipientId, NotificationType.MESSAGE_RECEIVED, message);
    }

    /**
     * B-17 (compliance plan C.9 — the trust &amp; verification sidecar):
     * the member's VERIFIED notification — the grant verdict's own
     * journey's arrival point (the platform identity §0.1 rule: a
     * notification for every event; an event without a listener is a
     * measured defect). The same delivery shape as the event points
     * above (in-app row always lands; WebSocket and email ride their L22
     * per-type/channel preferences). The recipient arrives resolved at
     * the source ({@code MembershipVerificationGrantedEvent} carries the
     * complete trust fact — member, neighborhood, membership row) — this
     * method delivers unconditionally, keeping the delivery contract one
     * shape for every caller (the onMessageReceived B-08 precedent). The
     * event-to-listener wiring LANDED (the CodeRabbit round-1 adoption):
     * the record lives in shared/api (the CR-4 placement) and
     * NotificationEventListener's onMembershipVerificationGranted delivers
     * on every grant publication.
     */
    public void onVerificationGranted(UUID userId, UUID locationId) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String message = text.compose("notification.MEMBERSHIP_VERIFIED", platform, locationId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(userId,
                NotificationType.MEMBERSHIP_VERIFIED.name(), message));
        if (preferences.isChannelEnabled(userId,
                NotificationType.MEMBERSHIP_VERIFIED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(userId,
                    text.compose("email.MEMBERSHIP_VERIFIED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(userId, NotificationType.MEMBERSHIP_VERIFIED, message);
    }

    /**
     * B-17 (compliance plan C.9): the REPORTER's adjudication
     * notification — every resolve outcome is the reporter's journey's
     * arrival ({@code RESOLVED} behind {@code HIDE_CONTENT} and
     * {@code DISMISSED} behind {@code DISMISS} alike; distinct from the
     * author's CONTENT_MODERATED alert, which is the hide fact alone).
     * The same delivery shape as the event points above. The event
     * carries the stored vocabulary as names ({@code targetType} /
     * {@code outcome} — the ContentModeratedEvent String precedent), and
     * the composed text renders each through the bundle's vocabulary
     * channel ({@code targettype.*} / {@code reportoutcome.*}) with the
     * honest raw ride-through for unknown names. The event-to-listener
     * wiring LANDED (the CodeRabbit round-1 adoption): the record lives
     * in shared/api (the CR-4 placement) and
     * NotificationEventListener's onContentReportResolved delivers on
     * every adjudication publication — the human resolve path and the
     * engine's automatic path alike.
     */
    public void onReportResolved(UUID reporterId, String targetType, UUID targetId, String outcome) {
        // B-11: the composed text rides the module's MessageSource channel
        // at the platform locale.
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        String targetWord = text.targetTypeWord(targetType, platform);
        String outcomeWord = text.reportOutcomeWord(outcome, platform);
        String message = text.compose("notification.REPORT_RESOLVED", platform,
                targetWord, outcomeWord, targetId);
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(reporterId,
                NotificationType.REPORT_RESOLVED.name(), message));
        if (preferences.isChannelEnabled(reporterId,
                NotificationType.REPORT_RESOLVED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(reporterId,
                    text.compose("email.REPORT_RESOLVED.subject", platform),
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(reporterId, NotificationType.REPORT_RESOLVED, message);
    }

    /**
     * Task 5-f (the discovery waves' measured repairs — the
     * events-without-consumers closure): the dispute opener's
     * DISPUTE_OPENED acknowledgment — the B-06 events' first delivery.
     *
     * <p><b>The recipient is the dispute's OPENER</b> — the honest reading
     * of the measured payload: {@code DisputeOpenedEvent} carries
     * {@code openedBy} and no counterpart party (the "other side" of a
     * dispute is the provider/consumer pair of the booking, resolvable
     * only by a cross-module re-derivation the complete-fact discipline
     * forbids). So the delivery is the OPENER's own acknowledgment: "your
     * dispute was opened and is under review" — a real journey arrival
     * (the pipeline's entry confirmed to the party who entered it), keyed
     * on the dispute's own id so a re-delivered publication can never
     * duplicate it.
     *
     * <p><b>The dedup ledger (this wave's measured mechanism):</b> the
     * pre-insert {@code existsByRecipientIdAndSourceEventId} gate answers
     * the sequential re-delivery (the framework's resubmission of an
     * incomplete registry entry) with an early return; the
     * {@code uq_notifications_source_event_once} partial unique index is
     * the concurrent-insert backstop — the rare lost race surfaces as a
     * {@code DataIntegrityViolationException}, which is THE WINNER'S
     * PROOF here (the row exists — the delivery happened) and is absorbed
     * quietly, the V150 messaging replay discipline's honest twin: never a
     * duplicate row, never a failed listener poisoning the registry.
     */
    public void onDisputeOpened(UUID disputeId, UUID openedBy) {
        if (repository.existsByRecipientIdAndSourceEventId(openedBy, disputeId)) {
            log.debug("Dispute-opened notification already delivered: disputeId={}, recipient={}",
                    disputeId, openedBy);
            return;
        }
        try {
            deliverDisputeNotification(openedBy, NotificationType.DISPUTE_OPENED,
                    text.compose("notification.DISPUTE_OPENED", NotificationTextSource.PLATFORM_LOCALE,
                            disputeId),
                    disputeId);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            log.info("Concurrent dispute-opened delivery lost the ledger race — the row exists: "
                    + "disputeId={}, recipient={}", disputeId, openedBy);
        }
    }

    /**
     * Task 5-f: the dispute opener's DISPUTE_RESOLVED adjudication fact —
     * the resolve decision's arrival (the {@code DisputeResolvedEvent}
     * carries the whole outcome: the stored resolution name and the
     * EXECUTED refunded total, the complete-fact discipline).
     *
     * <p><b>The ledger key is DETERMINISTIC</b> —
     * {@code UUID.nameUUIDFromBytes(disputeId + "-resolved")}: a resolve
     * publication re-delivered by the framework derives the SAME
     * source_event_id every time, so the dedup ledger holds exactly-once
     * across arbitrary re-delivery by construction (an
     * open-then-resolve-then-...-re-resolve is impossible — the
     * OPEN→RESOLVED transition is validated 409-once at the source; the
     * derivation needs no per-attempt salt).
     */
    public void onDisputeResolved(UUID disputeId, UUID openedBy, String resolution,
                                  Long refundedAmountCents) {
        UUID sourceEventId = UUID.nameUUIDFromBytes(
                (disputeId + "-resolved").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (repository.existsByRecipientIdAndSourceEventId(openedBy, sourceEventId)) {
            log.debug("Dispute-resolved notification already delivered: disputeId={}, recipient={}",
                    disputeId, openedBy);
            return;
        }
        try {
            Locale platform = NotificationTextSource.PLATFORM_LOCALE;
            String resolutionWord = text.disputeResolutionWord(resolution, platform);
            String message = refundedAmountCents != null
                    ? text.compose("notification.DISPUTE_RESOLVED.refund", platform,
                            resolutionWord, refundedAmountCents, disputeId)
                    : text.compose("notification.DISPUTE_RESOLVED", platform, resolutionWord, disputeId);
            deliverDisputeNotification(openedBy, NotificationType.DISPUTE_RESOLVED,
                    message, sourceEventId);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            log.info("Concurrent dispute-resolved delivery lost the ledger race — the row exists: "
                    + "disputeId={}, recipient={}", disputeId, openedBy);
        }
    }

    /**
     * Task 5-f: the shared dispute delivery shape — the same delivery shape
     * as the event points above (in-app row always lands; WebSocket and
     * email ride their L22 per-type/channel preferences). The recipient is
     * the dispute's opener in the users.id space — the id IS the recipient,
     * the same seam every carried-fact delivery here uses.
     */
    private void deliverDisputeNotification(UUID recipientId, NotificationType type,
                                            String message, UUID sourceEventId) {
        repository.save(Notification.create(recipientId, type.name(), message, sourceEventId));
        if (preferences.isChannelEnabled(recipientId, type, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipientId,
                    text.compose("email." + type.name() + ".subject", NotificationTextSource.PLATFORM_LOCALE),
                    "email/notification",
                    Map.of("message", message));
        }
        sendWebSocket(recipientId, type, message);
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(Authentication authentication, Pageable pageable) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return repository.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)
                .map(NotificationResponse::from);
    }

    /**
     * Plan item 2.6: the unread badge count — a read-only COUNT over the
     * recipient's unread rows, the polling endpoint's single number.
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return repository.countByRecipientIdAndReadIsFalse(userId);
    }

    @Observed(name = "notification.mark.read")
    public NotificationResponse markAsRead(UUID id, Authentication authentication) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        if (!notification.getRecipientId().equals(userId) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("Not allowed to access this notification");
        }
        notification.markRead();
        // Flush the managed update BEFORE mapping the response so the wire
        // metadata (version, updatedAt) reflects the persisted row, not the
        // pre-flush in-memory state (CodeRabbit review on #427; the same
        // staleness existed when the controller serialized the entity — the
        // DTO boundary makes it explicit and fixable). saveAndFlush runs in
        // the repository's own transaction (SimpleJpaRepository pattern).
        repository.saveAndFlush(notification);
        return NotificationResponse.from(notification);
    }

    /**
     * B-07 (compliance plan 0.8 — the measured defect §3.4-6): delete one
     * notification — the caller's own only (the same ownership discipline
     * {@link #markAsRead} carries: the recipient or an admin). The delete
     * is the BaseEntity soft delete ({@code @SoftDelete} — {@code is_deleted}),
     * so the Envers trace and the audit row survive.
     */
    @Observed(name = "notification.delete")
    public void delete(UUID id, Authentication authentication) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        if (!notification.getRecipientId().equals(userId) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("Not allowed to access this notification");
        }
        repository.delete(notification);
    }

    /**
     * B-07 (0.8): mark ALL the caller's unread notifications as read — the
     * feed's clear-all (one bulk UPDATE, the count returned for the badge's
     * immediate reconciliation). Only the CALLER's rows: an admin clearing
     * their own feed, never anyone else's.
     */
    @Observed(name = "notification.mark.all.read")
    public int markAllAsRead(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return repository.markAllAsReadByRecipientId(userId);
    }
}
