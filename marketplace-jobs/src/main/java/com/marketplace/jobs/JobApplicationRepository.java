package com.marketplace.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * B-12 (compliance plan C.2): the application repository — the employer
 * inbox read, the seeker's own read, and the one-live-application
 * pre-check; the V153 partial unique index is the structural backstop
 * behind the service's check (the V5/V150 idempotency discipline: the
 * check answers politely, the index answers the race).
 */
public interface JobApplicationRepository extends JpaRepository<JobApplication, UUID> {

    /** The one-live-application pre-check — (job, seeker) over the live rows. */
    Optional<JobApplication> findByJobIdAndSeekerId(UUID jobId, UUID seekerId);

    /** The employer's inbox for one job — the service passes the stable sort. */
    Page<JobApplication> findByJobId(UUID jobId, Pageable pageable);

    /** The inbox filtered by status (the NEW queue, the decided history). */
    Page<JobApplication> findByJobIdAndStatus(UUID jobId, ApplicationStatus status, Pageable pageable);

    /** The seeker's own applications — every job they applied to, newest first. */
    Page<JobApplication> findBySeekerId(UUID seekerId, Pageable pageable);

    /** The seeker's own filtered by status. */
    Page<JobApplication> findBySeekerIdAndStatus(UUID seekerId, ApplicationStatus status, Pageable pageable);
}
