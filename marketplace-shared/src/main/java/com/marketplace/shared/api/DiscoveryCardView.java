package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The discovery projection's card contract (execution-plan unified §1.4 +
 * the #536 backend-execution "عقد إسقاط الاكتشاف"): one source record
 * rendered as an exploratory card — never a content copy.
 *
 * <p><b>The projection's legal identity:</b> every card carries its
 * ORIGINAL record identity ({@code sourceType} + {@code sourceId}) and the
 * source's version moment ({@code updatedAt}); the owning module re-checks
 * state and visibility when the record is opened (AC-20-10: a withdrawn /
 * resolved / corrected source reflects its CURRENT state, and a stale
 * projection can never outlive its source — the event_publication +
 * withdrawn-source discipline this platform already runs for knowledge and
 * AI retrieval). A card is NEVER duplicated inside one rail
 * (AC-20-03: dedup by the source pair is the rail assembler's duty), and
 * two separate contexts sharing one card govern their impression dedup
 * through the announced display contract (the discovery_impressions
 * ledger), not through content copies.</p>
 *
 * <param name="sourceType">the owning module's record type — the closed
 *     vocabulary: {@code NEIGHBORHOOD_POST}, {@code NEIGHBORHOOD_EVENT},
 *     {@code JOB}, {@code URGENT_ALERT}, {@code PROVIDER_LISTING}</param>
 * <param name="sourceId">the original record's id in its owner's id space
 *     (the V32 discipline — plain UUID, never a cross-module relation)</param>
 * <param name="updatedAt">the source record's version moment — the
 *     "version/updated-at" leg of the projection contract</param>
 * <param name="title">the card's human title (source-derived, never
 *     AI-invented — AC-20-09)</param>
 * <param name="snippet">a bounded honest excerpt of the source content</param>
 * <param name="state">the source's CURRENT lifecycle state (e.g.
 *     {@code ACTIVE}/{@code RESOLVED} for LOST_FOUND, {@code ACTIVE}/
 *     {@code CANCELLED}/{@code POSTPONED} for events, {@code OPEN} for
 *     jobs) — surfaced, never hidden</param>
 * <param name="scopeLocationId">the geo_locations id (level-3
 *     neighborhood space) the card is scoped to, when the source is
 *     geo-scoped; null for user-scoped sources</param>
 * <param name="reason">the human-explainable reason this card appears
 *     (AC-20-06 + AC-20-11 discipline: the user can understand and correct
 *     it — "في نطاق حيّك", "من مصادر أتابعها", "حديث في حيّك"); never
 *     empty on the FOR_YOU rail</param>
 * <param name="paid">the paid/organic disclosure — {@code true} marks a
 *     paid placement that must never masquerade as an organic
 *     recommendation (AC-20-08); {@code null} = organic (all current
 *     sources are organic)</param>
 */
public record DiscoveryCardView(
        String sourceType,
        UUID sourceId,
        Instant updatedAt,
        String title,
        String snippet,
        String state,
        UUID scopeLocationId,
        String reason,
        Boolean paid) {
}
