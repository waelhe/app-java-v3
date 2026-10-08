package com.marketplace.jobs;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B-12 (compliance plan C.2): the jobs engine's contracts — the guard
 * order (existence → shape → state → identity), the ownership discipline
 * (the caller's own resource or 404), the one-way machines, and the full
 * journey (post → discover → apply → decide) as one orchestration pin.
 */
@ExtendWith(MockitoExtension.class)
class JobsServiceTest {

    private static final UUID EMPLOYER_ID = UUID.randomUUID();
    private static final UUID SEEKER_ID = UUID.randomUUID();

    @Mock
    private JobListingRepository jobRepository;

    @Mock
    private JobApplicationRepository applicationRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    private JobsService service() {
        return new JobsService(jobRepository, applicationRepository, currentUserProvider, clock);
    }

    private void callerIs(UUID userId) {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
    }

    private JobRequest request() {
        return new JobRequest("مطلوب مصمم واجهات", "وصف الوظيفة والمهام والمتطلبات",
                EmploymentType.FULL_TIME, WorkplaceType.HYBRID, "الرياض", "حي القضية",
                500_000L, 900_000L, "SAR", now.plus(Duration.ofDays(30)));
    }

    @Test
    void createIsBornActiveWithTheCallerAsEmployer() {
        callerIs(EMPLOYER_ID);
        when(jobRepository.save(any(JobListing.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JobListing job = service().create(request(), authentication);

        assertThat(job.getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(job.getEmployerId()).isEqualTo(EMPLOYER_ID);
        assertThat(job.getTitle()).isEqualTo("مطلوب مصمم واجهات");
        assertThat(job.getSalaryMinCents()).isEqualTo(500_000L);
        assertThat(job.getSalaryCurrency()).isEqualTo("SAR");
        assertThat(job.getApplicationDeadline()).isEqualTo(now.plus(Duration.ofDays(30)));
    }

    @Test
    void createRejectsTheHalfSalaryBlockLoudly() {
        callerIs(EMPLOYER_ID);
        JobRequest halfBlock = new JobRequest("title", "description",
                EmploymentType.PART_TIME, WorkplaceType.REMOTE, "جدة", null,
                100L, null, null, null);

        assertThatThrownBy(() -> service().create(halfBlock, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all-or-nothing");
        verifyNoInteractions(jobRepository);
    }

    @Test
    void createRejectsTheInvertedSalaryBoundsLoudly() {
        callerIs(EMPLOYER_ID);
        JobRequest inverted = new JobRequest("title", "description",
                EmploymentType.CONTRACT, WorkplaceType.ONSITE, "جدة", null,
                900L, 100L, "SAR", null);

        assertThatThrownBy(() -> service().create(inverted, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor cannot exceed");
    }

    @Test
    void boardDefaultsToActiveAndPassesEveryFilter() {
        Pageable pageable = PageRequest.of(0, 20);
        when(jobRepository.searchBoard(JobStatus.ACTIVE, "الرياض", EmploymentType.FULL_TIME,
                WorkplaceType.HYBRID, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service().searchBoard(null, "الرياض", EmploymentType.FULL_TIME, WorkplaceType.HYBRID, pageable);

        verify(jobRepository).searchBoard(JobStatus.ACTIVE, "الرياض", EmploymentType.FULL_TIME,
                WorkplaceType.HYBRID, pageable);
    }

    @Test
    void detailUnknownJobIs404() {
        UUID id = create(UUID.class);
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getJob(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found");
    }

    @Test
    void closeMovesTheOwnersActiveJobToClosed() {
        callerIs(EMPLOYER_ID);
        UUID jobId = create(UUID.class);
        JobListing job = JobListing.create(EMPLOYER_ID, "title", "description",
                EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                null, null, null, null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        JobListing closed = service().close(jobId, authentication);

        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    @Test
    void closeOfAForeignJobAnswers404NeverA403() {
        callerIs(EMPLOYER_ID);
        UUID jobId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(UUID.randomUUID(), "title", "description",
                        EmploymentType.VOLUNTEER, WorkplaceType.ONSITE, "الرياض", null,
                        null, null, null, null)));

        assertThatThrownBy(() -> service().close(jobId, authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found");
    }

    @Test
    void closeOfAnAlreadyClosedJobIs400BeforeAnyMutation() {
        callerIs(EMPLOYER_ID);
        UUID jobId = create(UUID.class);
        JobListing job = JobListing.create(EMPLOYER_ID, "title", "description",
                EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                null, null, null, null);
        job.close();
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service().close(jobId, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void applyLandsNewInTheEmployersInbox() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        JobListing job = JobListing.create(EMPLOYER_ID, "title", "description",
                EmploymentType.INTERNSHIP, WorkplaceType.HYBRID, "الرياض", null,
                null, null, null, now.plus(Duration.ofDays(10)));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(applicationRepository.findByJobIdAndSeekerId(jobId, SEEKER_ID))
                .thenReturn(Optional.empty());
        when(applicationRepository.save(any(JobApplication.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JobApplication application = service().apply(jobId,
                new JobApplicationRequest("رسالة تقديم"), authentication);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.NEW);
        assertThat(application.getSeekerId()).isEqualTo(SEEKER_ID);
        assertThat(application.getJobId()).isEqualTo(jobId);
    }

    @Test
    void applyToTheEmployersOwnJobIs400() {
        callerIs(EMPLOYER_ID);
        UUID jobId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(EMPLOYER_ID, "title", "description",
                        EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                        null, null, null, null)));

        assertThatThrownBy(() -> service().apply(jobId,
                new JobApplicationRequest("msg"), authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("your own job");
    }

    @Test
    void applyToAClosedJobIs409() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        JobListing job = JobListing.create(EMPLOYER_ID, "title", "description",
                EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                null, null, null, null);
        job.close();
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service().apply(jobId,
                new JobApplicationRequest("msg"), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not accepting");
    }

    @Test
    void applyPastTheDeadlineIs409() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        JobListing job = JobListing.create(EMPLOYER_ID, "title", "description",
                EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                null, null, null, now.minus(Duration.ofDays(1)));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service().apply(jobId,
                new JobApplicationRequest("msg"), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    void applyWithALiveApplicationIs409() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(EMPLOYER_ID, "title", "description",
                        EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                        null, null, null, null)));
        when(applicationRepository.findByJobIdAndSeekerId(jobId, SEEKER_ID))
                .thenReturn(Optional.of(JobApplication.create(jobId, SEEKER_ID, "السابقة")));

        assertThatThrownBy(() -> service().apply(jobId,
                new JobApplicationRequest("msg"), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Already applied");
    }

    @Test
    void jobInboxOfAForeignJobAnswers404() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(EMPLOYER_ID, "title", "description",
                        EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                        null, null, null, null)));

        assertThatThrownBy(() -> service().jobInbox(jobId, authentication, null,
                PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found");
        verify(applicationRepository, never()).findByJobIdOrderByCreatedAtDescIdDesc(any(), any());
    }

    @Test
    void moveApplicationWalksTheOneWayGraph() {
        callerIs(EMPLOYER_ID);
        UUID jobId = create(UUID.class);
        UUID applicationId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(EMPLOYER_ID, "title", "description",
                        EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                        null, null, null, null)));
        JobApplication application = JobApplication.create(jobId, SEEKER_ID, "msg");
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.of(application));

        service().moveApplication(jobId, applicationId, ApplicationStatus.REVIEWED, authentication);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REVIEWED);

        service().moveApplication(jobId, applicationId, ApplicationStatus.ACCEPTED, authentication);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);
    }

    @Test
    void moveApplicationOfAnIllegalJumpIs400() {
        callerIs(EMPLOYER_ID);
        UUID jobId = create(UUID.class);
        UUID applicationId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(EMPLOYER_ID, "title", "description",
                        EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                        null, null, null, null)));
        JobApplication application = JobApplication.create(jobId, SEEKER_ID, "msg");
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.of(application));

        // NEW → ACCEPTED skips REVIEWED: the one-way graph says no.
        assertThatThrownBy(() -> service().moveApplication(jobId, applicationId,
                ApplicationStatus.ACCEPTED, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot move");
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.NEW);
    }

    @Test
    void moveApplicationUnderAForeignJobAnswers404() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(
                JobListing.create(EMPLOYER_ID, "title", "description",
                        EmploymentType.FULL_TIME, WorkplaceType.REMOTE, "الرياض", null,
                        null, null, null, null)));

        assertThatThrownBy(() -> service().moveApplication(jobId, create(UUID.class),
                ApplicationStatus.REVIEWED, authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found");
    }

    @Test
    void withdrawSoftDeletesTheSeekersOwnApplication() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        UUID applicationId = create(UUID.class);
        JobApplication application = JobApplication.create(jobId, SEEKER_ID, "msg");
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.of(application));

        service().withdraw(jobId, applicationId, authentication);

        verify(applicationRepository).delete(application);
    }

    @Test
    void withdrawOfAForeignApplicationAnswers404() {
        callerIs(SEEKER_ID);
        UUID jobId = create(UUID.class);
        UUID applicationId = create(UUID.class);
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.of(
                JobApplication.create(jobId, UUID.randomUUID(), "msg")));

        assertThatThrownBy(() -> service().withdraw(jobId, applicationId, authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Application not found");
        verify(applicationRepository, never()).delete(any());
    }

    /**
     * The module's DoD as a journey (the plan's §1.2 rule): post →
     * discover → apply → decide — one orchestration pin over the mocked
     * collaborators, the same sequence the app-level IT will ride against
     * a real database in CI.
     */
    @Test
    void theFullJourneyPostDiscoverApplyDecide() {
        JobsService service = service();
        callerIs(EMPLOYER_ID);
        when(jobRepository.save(any(JobListing.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. The employer posts — born ACTIVE.
        JobListing job = service.create(request(), authentication);
        assertThat(job.getStatus()).isEqualTo(JobStatus.ACTIVE);

        // 2. The board returns it (the discoverable surface).
        when(jobRepository.searchBoard(JobStatus.ACTIVE, null, null, null,
                PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(job), PageRequest.of(0, 20), 1));
        assertThat(service.searchBoard(null, null, null, null, PageRequest.of(0, 20)))
                .hasSize(1);

        // 3. The seeker applies — 201 into the employer's inbox.
        callerIs(SEEKER_ID);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(applicationRepository.findByJobIdAndSeekerId(job.getId(), SEEKER_ID))
                .thenReturn(Optional.empty());
        when(applicationRepository.save(any(JobApplication.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        JobApplication application = service.apply(job.getId(),
                new JobApplicationRequest("أرغب بالانضمام لفريقكم"), authentication);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.NEW);

        // 4. The employer reads the inbox and decides.
        callerIs(EMPLOYER_ID);
        when(applicationRepository.findByJobIdOrderByCreatedAtDescIdDesc(job.getId(), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(application), PageRequest.of(0, 20), 1));
        assertThat(service.jobInbox(job.getId(), authentication, null, PageRequest.of(0, 20)))
                .hasSize(1);
        when(applicationRepository.findById(application.getId()))
                .thenReturn(Optional.of(application));

        service.moveApplication(job.getId(), application.getId(),
                ApplicationStatus.REVIEWED, authentication);
        service.moveApplication(job.getId(), application.getId(),
                ApplicationStatus.ACCEPTED, authentication);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);

        // 5. The closed job stops accepting (the board's honest state).
        service.close(job.getId(), authentication);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CLOSED);
        callerIs(SEEKER_ID);
        assertThatThrownBy(() -> service.apply(job.getId(),
                new JobApplicationRequest("متأخر"), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not accepting");
    }
}
