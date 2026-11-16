package com.marketplace.jobs;

/**
 * B-12 (compliance plan C.2): the job listing's lifecycle — the
 * {@code ListingStatus} house state-machine pattern sized to this domain:
 * two states, one move. A job is born {@code ACTIVE} (the posting journey
 * has no draft leg — the employer composes the post and it answers 201
 * visible on the board, the {@code Review.create} precedent of
 * create-and-be-seen), and closing is the one-way move ({@code CLOSED}
 * stays readable at its detail for anyone holding the id — the row is a
 * fact, the board simply stops returning it). Withdrawal is the BaseEntity
 * soft delete — the row keeps its audit trail, the reads stop returning
 * it.
 */
public enum JobStatus {

    ACTIVE,
    CLOSED;

    private static final java.util.Map<JobStatus, java.util.Set<JobStatus>> TRANSITIONS =
            java.util.Map.of(
                    ACTIVE, java.util.Set.of(CLOSED),
                    CLOSED, java.util.Set.of());

    /**
     * The one-way gate: ACTIVE→CLOSED, CLOSED is terminal. Every mutation
     * goes through this check so an illegal move fails loudly in the
     * service instead of silently rewriting history (the
     * {@code LeadStatus}/{@code ListingStatus} house discipline).
     */
    public boolean canTransitionTo(JobStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
