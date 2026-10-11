package com.marketplace.jobs.spi;

import com.marketplace.jobs.JobListingRepository;
import com.marketplace.shared.api.JobsDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * JT-20 — the jobs discovery adapter's honest contract (the CodeRabbit
 * round on the #540 slice), unit-pinned on the {@code MediaLookupAdapterTest}
 * shape (plain Mockito, no Spring):
 *
 * <ul>
 *   <li>THE PORT CONTRACT'S SECOND OPTION, TAKEN HONESTLY: the module
 *       holds no neighborhood geo scoping (city/district are free text,
 *       V153 — not geo_locations ids), so the records DO NOT ENTER this
 *       query. The adapter answers the honest empty page for EVERY
 *       locationId — never a silent national widening labelled with
 *       neighborhood reasons ("فرصة قريبة" / "حديث في حيّك");</li>
 *   <li>the repository is never consulted — the decision is structural
 *       (no mapping exists), not a query-level filter;</li>
 *   <li>the empty answer keeps the shared envelope's honest shape: the
 *       caller's own page/size, zero totals, last=true — the §9.4
 *       degraded-leg form (an empty leg, never a dead rail).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobsDiscoveryAdapterTest {

    @Mock
    private JobListingRepository jobRepository;

    private JobsDiscoveryAdapter adapter() {
        return new JobsDiscoveryAdapter(jobRepository);
    }

    @Test
    void findOpenJobs_isTheHonestEmptyLeg_theContractSecondOption() {
        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> response =
                adapter().findOpenJobs(UUID.randomUUID(), PagedRequest.of(0, 20));

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.last()).isTrue();
        // The repository is never consulted — the records do not enter
        // the query at all (the port's second option, its own honesty
        // rule: "either it scopes or the record does not enter this
        // query — no silent widening").
        verifyNoInteractions(jobRepository);
    }

    @Test
    void findOpenJobs_answersTheSameHonestEmpty_forEveryLocation() {
        UUID firstLocation = UUID.randomUUID();
        UUID secondLocation = UUID.randomUUID();

        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> first =
                adapter().findOpenJobs(firstLocation, PagedRequest.of(0, 20));
        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> second =
                adapter().findOpenJobs(secondLocation, PagedRequest.of(0, 20));

        // Location-independent honesty: no locationId can scope the
        // free-text fields, so every caller sees the same empty leg —
        // never a location-flavored widening.
        assertThat(first.content()).isEmpty();
        assertThat(second.content()).isEmpty();
        verifyNoInteractions(jobRepository);
    }

    @Test
    void findOpenJobs_keepsTheCallersOwnEnvelopeShape() {
        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> response =
                adapter().findOpenJobs(UUID.randomUUID(), PagedRequest.of(2, 5));

        // The caller's own page/size ride the honest empty envelope —
        // the degraded-leg form the shared ports already speak.
        assertThat(response.pageNumber()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(5);
        assertThat(response.last()).isTrue();
    }
}
