package com.marketplace.jobs.spi;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.shared.api.JobsDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;

/**
 * JT-20 (#536 discovery waves D1-D4): the jobs module's implementation
 * of the {@link JobsDiscoveryPort} cross-module read contract — the
 * "opportunities" leg of the EVENTS_AND_OPPORTUNITIES rail, on the
 * {@code PostLookupAdapter} house pattern (the interface lives in
 * shared-api, the data owner implements it, the consumer injects it —
 * no cross-module repository access).
 *
 * <p><b>THE PORT CONTRACT'S SECOND OPTION, TAKEN HONESTLY (CodeRabbit
 * round on the #540 slice):</b> the port's own javadoc gives the adapter
 * exactly two legitimate answers for a record it cannot scope — scope
 * the record to the caller's neighborhood, or keep it OUT of this query
 * ("either it scopes or the record does not enter this query — no
 * silent widening"). The jobs module holds NO geo scoping in the
 * neighborhood vocabulary: its {@code city}/{@code district} are
 * free-text display fields (the reviews-pattern self-containment,
 * V153), NOT geo_locations ids, so a level-3 {@code locationId} cannot
 * scope them without a mapping that does not exist. Serving every
 * ACTIVE job — labelled by the rails with neighborhood reasons
 * ("فرصة قريبة" / "حديث في حيّك") — was the silent national widening
 * the contract forbids: a member of one city saw another city's jobs
 * presented as local. Until the product-level geo mapping decision
 * lands (a migration + an employer-facing picker — a FUTURE decision,
 * never a silent text-match guess here), this adapter answers the
 * contract's second option: the honest empty page, the §9.4
 * degraded-leg form every source leg already speaks (an empty leg
 * labelled by its own truth, never fabricated cards, never a dead
 * answer for the whole rail — the events leg of
 * EVENTS_AND_OPPORTUNITIES keeps serving).
 */
@Component
@Transactional(readOnly = true)
public class JobsDiscoveryAdapter implements JobsDiscoveryPort {

    @Override
    public PagedResponse<DiscoveryJobCard> findOpenJobs(UUID locationId, PagedRequest page) {
        // locationId cannot scope the free-text city/district fields — the
        // records do NOT enter this query (the port's second option, its
        // own honesty rule). The jobs rail's live surface returns with the
        // geo mapping decision (see the class javadoc).
        return PagedResponse.empty(page);
    }
}
