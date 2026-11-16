package com.marketplace.shared.api;

/**
 * The closed vocabulary of the home discovery rails (the execution-plan
 * unified §1.4 functional vision — "صفوف اكتشاف موضوعية فوق سجل موحد").
 *
 * <p><b>The rail is an exploration surface, not a record type</b>: no rail
 * ever owns a table or a duplicated copy of content — every card resolves
 * back to its source record (the projection contract:
 * {@code sourceId + sourceType + version/updatedAt}, re-validated at open
 * time by the owning module). Rows whose eligibility is empty are OMITTED
 * from the response entirely (JT-20 / AC-20-07 — no empty rail is ever
 * rendered), so this enum enumerates the POSSIBLE rails, not the ones a
 * caller will see.</p>
 *
 * <p><b>The dimensions stay separate (§1.4):</b> purpose, topics, geo
 * scope, source/follow relation, lifecycle, urgency/delegation and the
 * paid/organic disclosure are per-card attributes — never merged into one
 * enum, never a post copy per rail.</p>
 */
public enum DiscoveryRowType {

    /**
     * CMP-46 / JT-10: delegated official alerts for the caller's
     * neighborhood — a dedicated surface ABOVE the ordinary rails, never
     * ranked by popularity or learned ordering (AC-20-06), carrying its
     * delegating source, scope and validity window.
     */
    URGENT_ALERTS,

    /**
     * §1.4 rail 2: the newest content of the sources the user explicitly
     * follows. "Follow" is never replaced by "for you" (AC-20-05) — this
     * rail exists precisely because an explicit choice must stay reachable.
     */
    FOLLOWED_SOURCES,

    /**
     * §1.4 rail 3: LOST_FOUND posts in the active lifecycle state only —
     * a resolved/recovered report never renders as active (JT-20 flow §5),
     * the user can flip the filter to see past states on the topic page.
     */
    LOST_FOUND,

    /**
     * §1.4 rail 4: RECOMMENDATION posts — every recommendation claim
     * resolves to its original post (no invented endorsements).
     */
    NEIGHBORHOOD_RECOMMENDATIONS,

    /**
     * §1.4 rail 5: upcoming neighborhood events plus open job
     * opportunities — two source types under one exploratory rail, each
     * card carrying its own sourceType and lifecycle state.
     */
    EVENTS_AND_OPPORTUNITIES,

    /**
     * §1.4 rail 6 ("اكتشف لك"): deterministic, explainable candidates
     * outside the user's direct follows — recency + declared scope, no
     * learned model (the ML step stays behind decision D-13 until a
     * measured baseline and an evaluation set exist), and every card
     * states WHY it appears.
     */
    FOR_YOU
}
