package com.marketplace.jobs.spi;

import com.marketplace.jobs.EmploymentType;
import com.marketplace.jobs.JobListing;
import com.marketplace.jobs.JobListingRepository;
import com.marketplace.jobs.JobStatus;
import com.marketplace.jobs.WorkplaceType;
import com.marketplace.shared.api.JobsDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JT-20 — the jobs discovery adapter's honest contract, unit-pinned on
 * the {@code MediaLookupAdapterTest} delegation shape (plain Mockito, no
 * Spring):
 *
 * <ul>
 *   <li>the lifecycle gate is the board's own, verbatim: the status is
 *       pinned to ACTIVE with every optional filter dropped — a CLOSED
 *       job never surfaces as an open opportunity (soft-deleted rows are
 *       absent through the entity's {@code @SoftDelete} filter);</li>
 *   <li>THE GEOGRAPHIC SCOPE FACT: the module holds no neighborhood
 *       scoping (city/district are free text, V153) — the adapter applies
 *       NO geographic filter, and the delegation is byte-identical for
 *       every locationId (the documented decision, never a silent
 *       widening or narrowing);</li>
 *   <li>the page/size ride the shared SpringPagination conversion,
 *       unsorted — the board's declared JPQL order (created_at DESC,
 *       id DESC) governs.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobsDiscoveryAdapterTest {

    @Mock
    private JobListingRepository jobRepository;

    private JobsDiscoveryAdapter adapter() {
        return new JobsDiscoveryAdapter(jobRepository);
    }

    private JobListing activeJob() {
        return JobListing.create(UUID.randomUUID(), "Barista wanted",
                "Morning shifts, friendly crew.", EmploymentType.PART_TIME,
                WorkplaceType.ONSITE, "الرياض", "النرجس",
                null, null, null, null);
    }

    @Test
    void findOpenJobs_pinsTheLifecycleGate_openMeansActive() {
        when(jobRepository.searchBoard(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        adapter().findOpenJobs(UUID.randomUUID(), PagedRequest.of(0, 20));

        // The board's own read with the status PINNED — a CLOSED (or
        // filled) job never enters the opportunities rail.
        verify(jobRepository).searchBoard(eq(JobStatus.ACTIVE), eq(null), eq(null),
                eq(null), any(Pageable.class));
    }

    @Test
    void findOpenJobs_appliesNoGeographicFilter_theDocumentedDecision() {
        when(jobRepository.searchBoard(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        UUID firstLocation = UUID.randomUUID();
        UUID secondLocation = UUID.randomUUID();
        adapter().findOpenJobs(firstLocation, PagedRequest.of(0, 20));
        adapter().findOpenJobs(secondLocation, PagedRequest.of(0, 20));

        // Byte-identical delegation for every locationId — no silent
        // text-match guess stands in for a product-level geo mapping.
        org.mockito.Mockito.verify(jobRepository, org.mockito.Mockito.times(2))
                .searchBoard(eq(JobStatus.ACTIVE), eq(null), eq(null), eq(null),
                        any(Pageable.class));
    }

    @Test
    void findOpenJobs_mapsTheCard_withTheHonestLifecycleStatus() {
        JobListing job = activeJob();
        when(jobRepository.searchBoard(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(job), PageRequest.of(0, 5), 1));

        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> response =
                adapter().findOpenJobs(UUID.randomUUID(), PagedRequest.of(0, 5));

        JobsDiscoveryPort.DiscoveryJobCard card = response.content().get(0);
        assertThat(card.jobId()).isEqualTo(job.getId());
        assertThat(card.title()).isEqualTo("Barista wanted");
        assertThat(card.description()).isEqualTo("Morning shifts, friendly crew.");
        assertThat(card.employmentType()).isEqualTo("PART_TIME");
        assertThat(card.workplaceType()).isEqualTo("ONSITE");
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(response.pageNumber()).isZero();
        assertThat(response.pageSize()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findOpenJobs_carriesThePageAndSize_unsorted() {
        when(jobRepository.searchBoard(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        adapter().findOpenJobs(UUID.randomUUID(), PagedRequest.of(0, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).searchBoard(any(), any(), any(), any(), pageable.capture());
        // The conversion carries page/size only — the board's declared
        // JPQL order (created_at DESC, id DESC) governs, unsorted input.
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
        // The shared interop corner is the one translator (round-trip
        // fidelity's documented home).
        assertThat(SpringPagination.toPageable(PagedRequest.of(0, 20)).getSort())
                .isEqualTo(Sort.unsorted());
    }
}
