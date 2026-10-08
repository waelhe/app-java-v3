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

    // The four inbox/seeker reads below carry the stable
    // (created_at DESC, id DESC) order in their derived names (the
    // idx_job_applications_job / idx_job_applications_seeker index
    // shapes, V153) — the CodeRabbit round-1 root adoption: the derived
    // name IS the house's documented way to pin the order (the
    // findByEmployerIdOrderByCreatedAtDescIdDesc precedent), so the
    // client Pageable can never leave the query without an ORDER BY
    // (rows could repeat or vanish across pages) nor inject arbitrary
    // sort properties.

    /** The employer's inbox for one job — newest first with the id tiebreaker. */
    Page<JobApplication> findByJobIdOrderByCreatedAtDescIdDesc(UUID jobId, Pageable pageable);

    /** The inbox filtered by status (the NEW queue, the decided history) — newest first with the id tiebreaker. */
    Page<JobApplication> findByJobIdAndStatusOrderByCreatedAtDescIdDesc(UUID jobId, ApplicationStatus status, Pageable pageable);

    /** The seeker's own applications — every job they applied to, newest first with the id tiebreaker. */
    Page<JobApplication> findBySeekerIdOrderByCreatedAtDescIdDesc(UUID seekerId, Pageable pageable);

    /** The seeker's own filtered by status — newest first with the id tiebreaker. */
    Page<JobApplication> findBySeekerIdAndStatusOrderByCreatedAtDescIdDesc(UUID seekerId, ApplicationStatus status, Pageable pageable);
}
