package com.marketplace.identity;

import com.marketplace.shared.api.BookingExportPort;
import com.marketplace.shared.api.MediaExportPort;
import com.marketplace.shared.api.MessagingExportData;
import com.marketplace.shared.api.MessagingExportPort;
import com.marketplace.shared.api.NotificationExportPort;
import com.marketplace.shared.api.ReviewExportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the identity module's export aggregation. Official
 * basis: GDPR Art. 20(1) — "the right to receive the personal data
 * concerning him or her, which he or she has provided to a controller, in
 * a structured, commonly used and machine-readable format".
 *
 * <p><b>The aggregation shape (the plan's R3 sentence, verbatim):</b> "each
 * module exports its share via its ports, and the aggregation happens in
 * identity through the SPI" — this service injects the five cross-module
 * export ports (shared-api contracts, implemented by the owning modules)
 * and composes their shares with identity's own profile section. No module
 * boundary is crossed: identity sees only shared-api types, exactly like
 * the standing {@code UserLookupPort}/{@code ReviewStatsPort} house
 * pattern, so {@code ModulithVerificationTest} stays the unedited guard.
 *
 * <p><b>Separate from pseudonymization (the plan's §5-ج opening):</b> the
 * export is a self-service read performed <em>before</em> and independently
 * of any pseudonymization — a live authenticated account. A pseudonymized
 * account cannot reach this surface (its login identity rows are gone, so
 * no token can be minted).
 *
 * <p><b>The execution record:</b> one structured log line (userId + section
 * sizes — content stays out of logs) accompanies the response's own
 * boundary notice, the same observability convention the account-status
 * and pseudonymization actions use.
 */
@Service
@Transactional(readOnly = true)
public class UserDataExportService {

    private static final Logger log = LoggerFactory.getLogger(UserDataExportService.class);

    private final BookingExportPort bookingExportPort;
    private final ReviewExportPort reviewExportPort;
    private final MessagingExportPort messagingExportPort;
    private final MediaExportPort mediaExportPort;
    private final NotificationExportPort notificationExportPort;

    public UserDataExportService(BookingExportPort bookingExportPort,
                                 ReviewExportPort reviewExportPort,
                                 MessagingExportPort messagingExportPort,
                                 MediaExportPort mediaExportPort,
                                 NotificationExportPort notificationExportPort) {
        this.bookingExportPort = bookingExportPort;
        this.reviewExportPort = reviewExportPort;
        this.messagingExportPort = messagingExportPort;
        this.mediaExportPort = mediaExportPort;
        this.notificationExportPort = notificationExportPort;
    }

    /**
     * Aggregates the data-subject export for a live account — one call per
     * module share, composed with the profile section and the boundary
     * notice.
     *
     * <p><b>No {@code @Observed} (the commands-not-reads policy, pinned by
     * {@code ObservationCoverageFilesTest}):</b> the export is a pure read —
     * the observation inventory reserves {@code @Observed} for business
     * commands. The execution record below is the auditable trace this
     * surface owes Art. 20, and it is a log line, not a metric.
     *
     * @param user the requester's live account row (the caller's
     *             {@code syncFromOidc} result — the /me bootstrap convention)
     */
    public UserDataExportResponse exportFor(User user) {
        var bookings = bookingExportPort.exportForParticipant(user.getId());
        var reviews = reviewExportPort.exportForAuthor(user.getId());
        MessagingExportData messaging = messagingExportPort.exportForParticipant(user.getId());
        var media = mediaExportPort.exportForOwner(user.getId());
        var notifications = notificationExportPort.exportForRecipient(user.getId());

        var response = new UserDataExportResponse(
                new UserDataExportResponse.ExportMetadata(
                        Instant.now(), UserDataExportResponse.SCOPE_NOTICE),
                new UserDataExportResponse.Profile(
                        user.getId(),
                        user.getSubject(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getRole().name(),
                        user.getCreatedAt(),
                        user.getUpdatedAt()),
                bookings,
                reviews,
                messaging.conversations(),
                messaging.messages(),
                media,
                notifications);

        // The execution record — section sizes only; exported content never
        // enters the log store (the same content-out discipline the
        // pseudonymization audit line applies against CWE-532).
        log.info("Data-subject export: userId={}, bookings={}, reviews={}, conversations={}, "
                        + "messages={}, media={}, notifications={}",
                user.getId(), bookings.size(), reviews.size(),
                messaging.conversations().size(), messaging.messages().size(),
                media.size(), notifications.size());
        return response;
    }
}
