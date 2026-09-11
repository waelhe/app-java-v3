package com.marketplace.shared.api;

import java.util.UUID;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the extended
 * purges, the plan's §7 Phase 3 row): the free-text purge contract. The
 * plan's purge option, verbatim: "تطهير نصوص مرسلها
 * ({@code messages.content}/{@code bookings.notes}…) — UPDATE عبر الجداول —
 * يُمسّ تاريخ الطرف المقابل؛ يُقيَّد بحقوق الآخرين (Art. 20(4))".
 *
 * <p>Each module owning free texts implements this port for its own tables
 * (the plan's R3 shape — the standing {@code BookingStatsPort} /
 * {@code BookingExportPort} house pattern), and the identity module
 * orchestrates the purge through all implementations. No module boundary is
 * crossed: identity sees only this shared-api type.
 *
 * <p><b>The purge contract (measured against the schema):</b>
 * <ul>
 *   <li><b>Base rows stay</b> — the shared record (booking, conversation,
 *       review, dispute, notification) keeps its structure, keys and
 *       non-text columns: reference integrity, the accounting source of
 *       truth, and the counterparty's rights (Art. 17(3)(b), Art. 20(4))
 *       keep the record alive — the plan's §4 rejection of hard deletes
 *       applies unchanged.</li>
 *   <li><b>Nullable text columns become NULL</b> — the Phase 1 storage
 *       convention ({@code email}/{@code display_name} := NULL).</li>
 *   <li><b>NOT NULL text columns become {@link #PURGED_MARKER}</b> —
 *       measured schema facts: {@code messages.content} (V7),
 *       {@code provider_profiles.display_name} (V14),
 *       {@code disputes.reason} (V20), {@code notifications.message}
 *       (V18), {@code provider_listings.title} (V2 — the §9 listings
 *       row's resolution, the eighth converter) are NOT NULL, so the
 *       tombstone marker is the honest representation the column
 *       constraint admits. The marker is what the counterparty sees on
 *       the shared record — the plan's declared Art. 20(4) trade-off made
 *       visible, not hidden.</li>
 *   <li><b>The Envers mirrors purge with the same shape</b> — every
 *       {@code *_aud} mirror of a purged text column carries the original
 *       text in its historical revisions (V24 convention: the mirrors keep
 *       writing). A purge that left the history intact would be cosmetic —
 *       the original text would survive in the audit trail, a silent debt.
 *       The mirrors' <em>text columns</em> are purged with the same
 *       predicate; the revision rows themselves stay (the record's
 *       status/financial history is the counterparty's audit trail —
 *       gate b-4's retention basis, not this gate's scope).</li>
 *   <li><b>Native SQL, deliberately</b> — the adapters issue JDBC UPDATEs
 *       (the {@code UserService} house pattern for cross-cutting
 *       maintenance statements): bulk JPQL and native SQL both bypass
 *       entity dirty-checking, but native SQL is the only channel that can
 *       touch the Envers mirrors at all (no entity maps them), so both the
 *       base and the mirror statements share one deterministic mechanism.
 *       No Envers revision is written for the purge itself — the purge's
 *       audit record is the orchestrator's structured log line (the
 *       payments convention, the Phase 1 precedent).</li>
 *   <li><b>Idempotence</b> — every statement filters out already-purged
 *       values (NULL / the marker), so a re-run matches zero rows and the
 *       counts are exact. The plan's §7 gradualism: each adapter runs in
 *       its own transaction; a partial failure resumes on re-run.</li>
 * </ul>
 *
 * <p><b>Scope (the plan's provenance rule — texts the subject authored,
 * measured):</b> the messages he sent, the notes on the bookings he
 * requested, the review comments he wrote, the review replies he
 * authored as the review's provider ({@code ReviewsService}: "Only the
 * reviewed provider can reply" — the gate's authorship model), his
 * provider persona's public name and bio, the listings he authored as a
 * provider (their titles and descriptions — {@code provider_listings}
 * via {@code CatalogController.create}'s stored user id, the §9 listings
 * row's resolution), the disputes he opened, his notification feed's
 * message bodies. The counterparty's texts never match the predicates.
 */
public interface AuthoredContentPurgePort {

    /**
     * The tombstone for NOT NULL text columns (measured: V7/V14/V18/V20/V2).
     * Bracketed so it is visually and programmatically distinguishable
     * from user-authored content; documented here as the single constant
     * every adapter and every reader shares.
     */
    String PURGED_MARKER = "[purged]";

    /**
     * Purges every free text this module holds that {@code userId}
     * authored — base table and Envers mirror, one transaction per module
     * (the plan's §2 b-3 gradualism shape). Returns the number of rows
     * whose text was actually purged (base + mirror); a re-run on an
     * already-purged subject returns zero.
     *
     * <p>The caller (identity's orchestrator) has already verified the
     * account is pseudonymized — the purge completes an erasure flow, it
     * never operates on a live account.
     */
    int purgeAuthoredTexts(UUID userId);
}
