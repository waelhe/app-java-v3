package com.marketplace.shared.api;

import java.util.UUID;

/**
 * B-17 (compliance plan C.9 — the trust &amp; verification sidecar): a
 * content report was ADJUDICATED. Published by the moderation resolve
 * command — {@code ContentReportService.resolveReport}, the ONE
 * transition out of OPEN — on the module's exposed {@code community}
 * NamedInterface, the additive-only registration the contracts ledger
 * carries (a NEW event; the record's placement in {@code shared/api}
 * and the publisher wiring rode CR-10 — the placement and both wiring
 * halves executed together).
 *
 * <p><b>Distinct from {@code ContentModeratedEvent} (the measured
 * boundary between the two):</b> the moderated event is the AUTHOR's
 * alert on the real {@code VISIBLE -> HIDDEN} transition alone (a hide
 * goal met); THIS event is the REPORTER's adjudication fact — every
 * resolve outcome fires it ({@code RESOLVED} behind {@code HIDE_CONTENT}
 * and {@code DISMISSED} behind {@code DISMISS}), because the reporter's
 * journey is "my report left the queue", whichever way the verdict went.
 * One event, one reporter, one verdict word.
 *
 * <p><b>The vocabulary is carried, not shared (the
 * {@code ContentModeratedEvent} String precedent):</b>
 * {@code targetType} and {@code outcome} ride as the STORED names
 * ({@code "POST"/"COMMENT"/"REVIEW"} and {@code "RESOLVED"/"DISMISSED"})
 * so the record stays free of community-domain enum types — the shape a
 * future move to {@code shared/api} needs (the contracts ledger's own
 * placement rule).
 *
 * <p>Published inside the resolver's own transaction (Modulith
 * {@code reference/events.html} — the registry entry commits atomically
 * with the report's close), consumed {@code AFTER_COMMIT} in the
 * listener's own {@code REQUIRES_NEW} unit.
 */
public record ContentReportResolvedEvent(
        UUID reportId,
        UUID reporterId,
        String targetType,
        UUID targetId,
        String outcome
) {
}
