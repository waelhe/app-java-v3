package com.marketplace.shared.api;

import java.util.List;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): one domain's contribution to the unified search.
 * The standing house pattern (the {@code AuthoredContentPurgePort} /
 * {@code BookingStatsPort} shape): each module owning a searchable domain
 * implements this port for its own tables, and the search module
 * orchestrates through all implementations. No module boundary is crossed
 * in either direction — the orchestrator sees only this shared-api type.
 *
 * <p><b>The visibility contract (the plan's «لا تسرب» gate):</b> an
 * adapter applies the SAME eligibility predicates its own public read
 * surfaces apply — status machines (VISIBLE-only posts), soft-delete
 * (withdrawn knowledge), lifecycle states (a rejected institution never
 * answers). The orchestrator re-checks nothing: the source is the
 * authority on what may be seen.
 *
 * <p><b>The failure contract:</b> a throwing adapter degrades its source
 * to absence (reported in {@link UnifiedSearchResponse#degradedSources});
 * one domain being down never fails the whole answer.
 */
public interface UnifiedSearchSourcePort {

    /** The source this adapter speaks for — one adapter per source. */
    UnifiedSearchSource source();

    /**
     * Up to {@link UnifiedSearchQuery#limitPerSource()} hits, ranked by the
     * source's own relevance order with its stable tiebreak. The adapter
     * must not filter, re-rank, or re-shape other sources' hits.
     */
    List<UnifiedSearchHit> search(UnifiedSearchQuery query);

    /**
     * The shared snippet discipline: a bounded, whitespace-collapsed
     * excerpt of the source's body text — never a new snippet engine, just
     * an honest bounded prefix (title already carries the head).
     */
    static String excerpt(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        String collapsed = text.strip().replaceAll("\\s+", " ");
        if (collapsed.codePointCount(0, collapsed.length()) <= maxCodePoints) {
            return collapsed;
        }
        int end = collapsed.offsetByCodePoints(0, maxCodePoints);
        return collapsed.substring(0, end) + "…";
    }
}
