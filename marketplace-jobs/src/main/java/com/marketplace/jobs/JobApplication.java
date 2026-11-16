package com.marketplace.jobs;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * B-12 (compliance plan C.2): the seeker's application — the mediated
 * contact between the market's two sides, on the {@code ListingLead}
 * house shape with one structural difference the authenticated domain
 * owns: the seeker IS the caller ({@code seeker_id} = the caller's
 * users.id — no name/phone attribution fields, the identity is already
 * known), and one LIVE application per (job, seeker) is the identity —
 * the V153 partial unique index over the live rows (the V70/V64 shape: a
 * withdrawn application can be re-submitted as a fresh row without
 * colliding with its own tombstone).
 *
 * <p>The application's lifecycle lives in {@link ApplicationStatus} — the
 * employer's one-way moves; the seeker's withdrawal is the BaseEntity
 * soft delete (the {@code listing_favorites} discipline: the row keeps
 * its audit trail, the reads stop returning it).</p>
 */
@Entity
@Table(name = "job_applications")
@Audited
public class JobApplication extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    /** The seeker's USER id (users.id space — the caller at submission time). */
    @Column(name = "seeker_id", nullable = false)
    private UUID seekerId;

    @Column(name = "cover_message", nullable = false, columnDefinition = "TEXT")
    private String coverMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationStatus status = ApplicationStatus.NEW;

    protected JobApplication() {
    }

    private JobApplication(UUID id, UUID jobId, UUID seekerId, String coverMessage) {
        this.id = id;
        this.jobId = jobId;
        this.seekerId = seekerId;
        this.coverMessage = coverMessage;
        this.status = ApplicationStatus.NEW;
    }

    /**
     * The submission factory: an application is born NEW — the employer's
     * inbox sees it in the same transaction ({@code Review.create}
     * precedent). The guards that make the submission legal (the job is
     * ACTIVE, the deadline has not passed, not the employer's own job, no
     * live application yet) live in the service — the factory owns only
     * the shape, exactly like its house precedents.
     */
    public static JobApplication create(UUID jobId, UUID seekerId, String coverMessage) {
        return new JobApplication(UUID.randomUUID(), jobId, seekerId, coverMessage);
    }

    /** The employer's one-way move — called only by the service after the {@code canTransitionTo} gate. */
    public void move(ApplicationStatus target) {
        this.status = target;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getSeekerId() { return seekerId; }
    public String getCoverMessage() { return coverMessage; }
    public ApplicationStatus getStatus() { return status; }
}
