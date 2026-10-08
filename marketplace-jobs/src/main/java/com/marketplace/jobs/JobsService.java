package com.marketplace.jobs;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * B-12 (compliance plan C.2 — the jobs module): the employment vertical's
 * journey engine, on the {@code ReviewsService}/{@code LeadsService}
 * house shapes — {@code @Transactional} service, the shared exception
 * vocabulary (RFC 7807 through the problem-detail layer), the
 * {@code CurrentUserProvider} /me seam, and ownership gates that answer
 * the foreign row with 404 (the {@code ListingLead} inbox discipline:
 * "it is not in your inbox" — never a 403 that confirms existence).
 *
 * <p><b>The journey this engine serves (the module's DoD as a journey):
 * post → discover → apply → decide.</b> The employer posts (201, born
 * ACTIVE on the board); anyone reads the board and the detail; the
 * seeker applies (201 into the employer's inbox — guarded: not the
 * employer's own job, the job still ACTIVE, the deadline not past, no
 * live application yet); the employer moves the application through its
 * one-way lifecycle; the seeker withdraws (the soft delete — the row
 * keeps its audit trail, the reads stop returning it, a re-apply starts
 * a fresh row).</p>
 */
@Service
@Transactional
public class JobsService {

    private final JobListingRepository jobRepository;
    private final JobApplicationRepository applicationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public JobsService(JobListingRepository jobRepository,
                       JobApplicationRepository applicationRepository,
                       CurrentUserProvider currentUserProvider,
                       Clock clock) {
        this.jobRepository = jobRepository;
        this.applicationRepository = applicationRepository;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    /**
     * The posting: the job is born ACTIVE — the create-and-be-seen
     * journey ({@code Review.create} precedent). The caller IS the
     * employer ({@code users.id} at the A1 seam — no profile
     * indirection). The bean validation on {@link JobRequest} ran before
     * this point; the salary block's all-or-nothing shape guard lives in
     * the {@code JobListing.create} factory with its own loud failure.
     */
    @Observed(name = "job.create")
    public JobListing create(JobRequest request, Authentication authentication) {
        UUID employerId = currentUserProvider.getCurrentUserId(authentication);
        JobListing job = JobListing.create(employerId,
                request.title(), request.description(),
                request.employmentType(), request.workplaceType(),
                request.city(), request.district(),
                request.salaryMinCents(), request.salaryMaxCents(), request.salaryCurrency(),
                request.applicationDeadline());
        return jobRepository.save(job);
    }

    /**
     * The board: the public read — ACTIVE by default (the discoverable
     * surface), every filter optional (null drops the predicate). The
     * stable {@code (created_at, id)} sort keeps the page boundary
     * deterministic (the L32/D-N5 lesson).
     */
    @Transactional(readOnly = true)
    public Page<JobListing> searchBoard(JobStatus status, String city,
                                        EmploymentType employmentType, WorkplaceType workplaceType,
                                        Pageable pageable) {
        return jobRepository.searchBoard(status != null ? status : JobStatus.ACTIVE,
                city, employmentType, workplaceType, pageable);
    }

    /** The detail read: any live job by id — the row is a fact, CLOSED included. Unknown is 404. */
    @Transactional(readOnly = true)
    public JobListing getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
    }

    /** The employer's own management list — every status, their posts only. */
    @Transactional(readOnly = true)
    public Page<JobListing> myJobs(Authentication authentication, Pageable pageable) {
        UUID employerId = currentUserProvider.getCurrentUserId(authentication);
        return jobRepository.findByEmployerIdOrderByCreatedAtDescIdDesc(employerId, pageable);
    }

    /**
     * The one-way close: ACTIVE→CLOSED (the {@code ListingStatus}
     * discipline — the board stops returning it, the detail stays honest).
     * A foreign job answers 404 (the {@code ListingLead} ownership
     * discipline — the caller's own resource or nothing); an illegal move
     * (closing a CLOSED job) answers 400 before any state is read.
     */
    @Observed(name = "job.close")
    public JobListing close(UUID jobId, Authentication authentication) {
        UUID employerId = currentUserProvider.getCurrentUserId(authentication);
        JobListing job = jobRepository.findById(jobId)
                .filter(j -> j.getEmployerId().equals(employerId))
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        if (!job.getStatus().canTransitionTo(JobStatus.CLOSED)) {
            throw new BadRequestException("Job is already closed: " + jobId);
        }
        job.close();
        return job;
    }

    /**
     * The application: the seeker's submission into the employer's inbox.
     * The guard order is the honest one — existence (404) before ownership
     * shape (own-job 400) before state (closed/deadline 409) before the
     * live-application identity (409) — each with the contract's own
     * words. The V153 partial unique index backstops the last guard
     * against the in-flight double-submit (the V5/V150 discipline).
     */
    @Observed(name = "job.application.create")
    public JobApplication apply(UUID jobId, JobApplicationRequest request, Authentication authentication) {
        UUID seekerId = currentUserProvider.getCurrentUserId(authentication);
        JobListing job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        if (job.getEmployerId().equals(seekerId)) {
            throw new BadRequestException("Cannot apply to your own job");
        }
        if (job.getStatus() != JobStatus.ACTIVE) {
            throw new ConflictException("Job is not accepting applications: " + jobId);
        }
        if (job.getApplicationDeadline() != null
                && clock.instant().isAfter(job.getApplicationDeadline())) {
            throw new ConflictException("Application deadline has passed for job: " + jobId);
        }
        if (applicationRepository.findByJobIdAndSeekerId(jobId, seekerId).isPresent()) {
            throw new ConflictException("Already applied to job: " + jobId);
        }
        return applicationRepository.save(JobApplication.create(jobId, seekerId, request.coverMessage()));
    }

    /**
     * The employer's inbox for one job — newest first, optionally by
     * status. A foreign job answers 404 (the inbox discipline).
     */
    @Transactional(readOnly = true)
    public Page<JobApplication> jobInbox(UUID jobId, Authentication authentication,
                                         ApplicationStatus status, Pageable pageable) {
        UUID employerId = currentUserProvider.getCurrentUserId(authentication);
        jobRepository.findById(jobId)
                .filter(j -> j.getEmployerId().equals(employerId))
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        return status != null
                ? applicationRepository.findByJobIdAndStatusOrderByCreatedAtDescIdDesc(jobId, status, pageable)
                : applicationRepository.findByJobIdOrderByCreatedAtDescIdDesc(jobId, pageable);
    }

    /** The seeker's own applications — every job they applied to, optionally filtered. */
    @Transactional(readOnly = true)
    public Page<JobApplication> myApplications(Authentication authentication,
                                               ApplicationStatus status, Pageable pageable) {
        UUID seekerId = currentUserProvider.getCurrentUserId(authentication);
        return status != null
                ? applicationRepository.findBySeekerIdAndStatusOrderByCreatedAtDescIdDesc(seekerId, status, pageable)
                : applicationRepository.findBySeekerIdOrderByCreatedAtDescIdDesc(seekerId, pageable);
    }

    /**
     * The employer's one-way move on an application (NEW→REVIEWED/REJECTED,
     * REVIEWED→ACCEPTED/REJECTED — {@link ApplicationStatus} owns the
     * graph). The ownership chain: the application's job must be the
     * caller's (a foreign pair answers 404 — never a 403 that confirms
     * existence), and an illegal move answers 400 BEFORE any state is
     * read (the {@code DisputeService} guard-order discipline).
     */
    @Observed(name = "job.application.move")
    public JobApplication moveApplication(UUID jobId, UUID applicationId,
                                          ApplicationStatus target, Authentication authentication) {
        UUID employerId = currentUserProvider.getCurrentUserId(authentication);
        // The ownership chain reads each row ONCE: the caller's own job
        // first (a foreign job answers 404 — never a 403 that confirms
        // existence), then the application under that job (a foreign or
        // misaligned pair answers 404 too).
        jobRepository.findById(jobId)
                .filter(j -> j.getEmployerId().equals(employerId))
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        JobApplication application = applicationRepository.findById(applicationId)
                .filter(a -> a.getJobId().equals(jobId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        if (!application.getStatus().canTransitionTo(target)) {
            throw new BadRequestException("Cannot move an application from "
                    + application.getStatus() + " to " + target);
        }
        application.move(target);
        return application;
    }

    /** The seeker's withdrawal — the BaseEntity soft delete (the
     * {@code listing_favorites} discipline: the row keeps its audit
     * trail, the reads stop returning it, and the V153 partial unique
     * lets a fresh application follow). The ownership chain: the
     * application must be the caller's own under that job (a foreign
     * pair answers 404).
     */
    @Observed(name = "job.application.withdraw")
    public void withdraw(UUID jobId, UUID applicationId, Authentication authentication) {
        UUID seekerId = currentUserProvider.getCurrentUserId(authentication);
        JobApplication application = applicationRepository.findById(applicationId)
                .filter(a -> a.getJobId().equals(jobId))
                .filter(a -> a.getSeekerId().equals(seekerId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        applicationRepository.delete(application);
    }
}
