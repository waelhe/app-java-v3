package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The jobs module's discovery projection source — the "opportunities" leg
 * of the EVENTS_AND_OPPORTUNITIES rail (execution-plan unified §1.4 rail
 * 5: "فعاليات وفرص قريبة" — the scope and time and state and expiry are
 * visible on the card).
 *
 * <p>House pattern ({@code PostLookupPort} verbatim): interface in
 * shared-api, marketplace-jobs implements it, discovery injects it. Only
 * OPEN job listings answer here — a closed/filled job is lifecycle-honest
 * and never surfaces as an open opportunity.</p>
 */
public interface JobsDiscoveryPort {

    /**
     * @param locationId the caller's level-3 neighborhood — jobs scoped to
     *        it first; when the jobs module holds no geo scoping for a
     *        record the adapter decides honestly (either it scopes or the
     *        record does not enter this query — no silent widening)
     */
    PagedResponse<DiscoveryJobCard> findOpenJobs(UUID locationId, PagedRequest page);

    /** One job listing as the projection speaks it. */
    record DiscoveryJobCard(
            UUID jobId,
            String title,
            String description,
            String employmentType,
            String workplaceType,
            String status,
            Instant updatedAt) {
    }
}
