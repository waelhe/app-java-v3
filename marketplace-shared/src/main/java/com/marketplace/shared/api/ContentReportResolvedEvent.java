package com.marketplace.shared.api;

import java.util.UUID;

/**
 * B-17 (compliance plan C.9 — the trust &amp; verification sidecar): a
 * content report was ADJUDICATED. Published by the moderation resolve
 * command — BOTH paths out of OPEN fire it: the human
 * {@code ContentReportService.resolveReport} and the automatic
 * {@code ModerationRuleEngine} — on the module's exposed {@code community}
 * NamedInterface, the additive-only registration the contracts ledger
 * carries.
 *
 * <p><b>Placement ruling (the CR-4 crossing, landed by the CodeRabbit
 * round-1 adoption — the contracts ledger §1.1 house convention
 * measured on every cross-boundary record the notifications listener
 * consumes):</b> this event crosses module boundaries, so its record
 * lives in {@code shared/api}. The consumer-side import needs NO new
 * module dependency this way: notifications already depends on shared,
 * and no pom anywhere changes. The record was moved byte-equivalent
 * from the community module (package declaration only — the
 * {@code MessageReceivedEvent} CR-4 flow verbatim).
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
 * so the record stays free of community-domain enum types.
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
