package com.marketplace.identity;

import com.marketplace.shared.api.BookingExportEntry;
import com.marketplace.shared.api.ConversationExportEntry;
import com.marketplace.shared.api.MediaExportEntry;
import com.marketplace.shared.api.MessageExportEntry;
import com.marketplace.shared.api.NotificationExportEntry;
import com.marketplace.shared.api.ReviewExportEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5 opened by the user's word): the data-subject export
 * response — a structured, commonly used, machine-readable JSON document
 * (Art. 20(1) verbatim: "in a structured, commonly used and machine-readable
 * format").
 *
 * <p><b>The boundary notice travels with the data (the plan's §5-ج closing
 * rule):</b> the {@link ExportMetadata} header documents the export's scope
 * and generation time inside the response itself — an auditable record of
 * the Art. 20 execution that survives any transport (machine-readable and
 * self-describing, unlike transport headers).
 *
 * <p><b>The sections (the plan's provenance contract, verbatim):</b> the
 * requester's account profile, his first-party bookings
 * (status/dates/amounts), the reviews and messages he authored, the
 * metadata of his media (never file bytes), and his notifications. Shared
 * records carry the counterparty as an opaque UUID only. Internal system
 * columns (version/is_deleted/created_by/updated_by), operational data
 * (the event archive), and every record where the requester is not a first
 * party are excluded — the explicit exclusion rule.
 */
public record UserDataExportResponse(
        ExportMetadata export,
        Profile profile,
        List<BookingExportEntry> bookings,
        List<ReviewExportEntry> reviews,
        List<ConversationExportEntry> conversations,
        List<MessageExportEntry> messages,
        List<MediaExportEntry> media,
        List<NotificationExportEntry> notifications
) {

    /**
     * The boundary notice (the plan's §5-ج: "رأس تعريفي يوثّق نطاق التصدير
     * وتاريخه") — the fixed scope statement plus the generation timestamp.
     */
    public record ExportMetadata(
            Instant generatedAt,
            String scopeNotice
    ) {
    }

    /**
     * The requester's account profile (P1/P2 of the plan's inventory): the
     * identifiers and profile fields the account row holds. The neutral
     * "Former member" rendering never applies here — a pseudonymized
     * account cannot authenticate, so an authenticated export is by
     * construction the live account's data.
     */
    public record Profile(
            UUID id,
            String subject,
            String email,
            String displayName,
            String role,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** The fixed scope statement — the contract the sections implement. */
    static final String SCOPE_NOTICE = """
            Personal data held by this controller and provided by you (GDPR Art. 20(1)) \
            — exported as structured, machine-readable JSON:
            your account profile; bookings where you are a first party (status, dates, amounts); \
            reviews and messages you authored; descriptive metadata of your media (never file bytes); \
            and your notifications.
            Shared records carry the counterparty as an opaque identifier only (no name, email, or profile). \
            Excluded: internal system columns, operational data (event archive), audit strings, \
            and any record where you are not a first party.
            The provider persona (provider profile) is outside this export contract \
            (account-pseudonymization-plan §5-ج provenance enumeration).""";
}
