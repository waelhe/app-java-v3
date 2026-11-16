package com.marketplace.jobs;

/**
 * B-12 (compliance plan C.2): the application's lifecycle in the
 * employer's inbox — the {@code LeadStatus} house state-machine pattern
 * sized to this domain: the seeker's submission is {@code NEW}; the
 * employer reviews it ({@code REVIEWED}), then decides —
 * {@code ACCEPTED} or {@code REJECTED} — both terminal. One-way only:
 * a decided application never reopens (the honest history — the row is
 * what happened), and a rejected pair can never collide with a fresh one
 * because the seeker's re-apply rides the withdrawn row's soft delete.
 */
public enum ApplicationStatus {

    NEW,
    REVIEWED,
    ACCEPTED,
    REJECTED;

    private static final java.util.Map<ApplicationStatus, java.util.Set<ApplicationStatus>> TRANSITIONS =
            java.util.Map.of(
                    NEW, java.util.Set.of(REVIEWED, REJECTED),
                    REVIEWED, java.util.Set.of(ACCEPTED, REJECTED),
                    ACCEPTED, java.util.Set.of(),
                    REJECTED, java.util.Set.of());

    /**
     * The one-way gate: NEW→REVIEWED/REJECTED,
     * REVIEWED→ACCEPTED/REJECTED, both decisions terminal. Every mutation
     * goes through this check so an illegal move fails loudly in the
     * service instead of silently rewriting history.
     */
    public boolean canTransitionTo(ApplicationStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
