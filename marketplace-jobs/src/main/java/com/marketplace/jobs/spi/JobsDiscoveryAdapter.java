package com.marketplace.jobs.spi;

import com.marketplace.jobs.JobListing;
import com.marketplace.jobs.JobListingRepository;
import com.marketplace.jobs.JobStatus;
import com.marketplace.shared.api.JobsDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * JT-20 (#536 discovery waves D1-D4): the jobs module's implementation
 * of the {@link JobsDiscoveryPort} cross-module read contract — the
 * "opportunities" leg of the EVENTS_AND_OPPORTUNITIES rail, on the
 * {@code PostLookupAdapter} house pattern (the interface lives in
 * shared-api, the data owner implements it, the consumer injects it —
 * no cross-module repository access).
 *
 * <p><b>The lifecycle gate is the board's own, verbatim:</b> the port's
 * "open jobs" is THIS module's {@code ACTIVE} — exactly what
 * {@code JobsController}'s board serves ("ACTIVE by default, CLOSED
 * reachable for the honest history"): the query reuses the board's own
 * {@code searchBoard} read with the status pinned to
 * {@link JobStatus#ACTIVE} and every optional filter dropped. A closed
 * or filled job is lifecycle-honest and never surfaces as an open
 * opportunity; soft-deleted rows are excluded by the entity's
 * {@code @SoftDelete} filter (no predicate of its own, by design). The
 * read is the board's declared JPQL, so the board's deterministic order
 * ({@code created_at DESC, id DESC} — the stable-boundary L32/D-N5
 * lesson) governs; the {@link SpringPagination} conversion carries the
 * page/size only, unsorted (a caller sort is not part of the port's
 * contract).
 *
 * <p><b>THE GEOGRAPHIC SCOPE FACT (stated explicitly, per the port's
 * own honesty contract — no silent widening, no silent narrowing):</b>
 * the jobs module holds NO geo scoping in the neighborhood vocabulary —
 * its {@code city}/{@code district} are free-text display fields (the
 * reviews-pattern self-containment, V153), NOT geo_locations ids, so a
 * level-3 {@code locationId} cannot scope them without a mapping that
 * does not exist. This adapter therefore applies NO geographic filter:
 * every ACTIVE job enters the rail, whoever posts it and wherever it
 * sits. <b>Scoping jobs to the caller's neighborhood (or their city) is
 * a FUTURE decision</b> that requires a product-level geo mapping (a
 * migration + an employer-facing picker), never a silent text-match
 * guess here — until that decision lands, the rail's honest contract is
 * "all open jobs, ordered newest-first".
 */
@Component
@Transactional(readOnly = true)
public class JobsDiscoveryAdapter implements JobsDiscoveryPort {

    private final JobListingRepository jobRepository;

    public JobsDiscoveryAdapter(JobListingRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public PagedResponse<DiscoveryJobCard> findOpenJobs(UUID locationId, PagedRequest page) {
        // locationId is deliberately NOT used as a filter — see the
        // geographic scope fact above (the honest decision is documented,
        // never silent on either side).
        Pageable pageable = SpringPagination.toPageable(page);
        Page<JobListing> result = jobRepository.searchBoard(
                JobStatus.ACTIVE, null, null, null, pageable);
        return PagedResponse.of(result.map(JobsDiscoveryAdapter::toCard));
    }

    /** One job listing as the projection speaks it — the lifecycle status rides the card. */
    private static JobsDiscoveryPort.DiscoveryJobCard toCard(JobListing job) {
        return new JobsDiscoveryPort.DiscoveryJobCard(
                job.getId(),
                job.getTitle(),
                job.getDescription(),
                job.getEmploymentType().name(),
                job.getWorkplaceType().name(),
                job.getStatus().name(),
                job.getUpdatedAt());
    }
}
