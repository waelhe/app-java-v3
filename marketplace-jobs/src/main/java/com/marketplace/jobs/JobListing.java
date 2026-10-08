package com.marketplace.jobs;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * B-12 (compliance plan C.2): the job listing — the market's employment
 * vertical's aggregate root, on the {@code Review} house shape
 * ({@code @Audited} over the full BaseEntity column set from day one —
 * the V25/V32 lesson — with the {@code @SoftDelete} withdrawal keeping
 * the audit trail).
 *
 * <p><b>The employer seam (the A1 convention, the measured V6/V2 facts):
 * {@code employer_id} carries the employer's USER id — the caller's own
 * {@code users.id} at creation time, the same single space
 * {@code reviews.provider_id} and {@code listing_favorites.user_id} ride.
 * No profile indirection, no second id space.</p>
 *
 * <p><b>The salary block</b> is all-or-nothing by the service guard (and
 * the V153 cross-column CHECK as the honest backstop): both bounds and
 * the currency present together, or none of them — a job that does not
 * disclose salary simply carries nulls, the house's "optional capability
 * column" discipline ({@code refundAmountCents} on disputes).</p>
 */
@Entity
@Table(name = "jobs")
@Audited
public class JobListing extends BaseEntity {

    @Id
    private UUID id;

    /** The employer's USER id (users.id space — the A1 convention). */
    @Column(name = "employer_id", nullable = false)
    private UUID employerId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "workplace_type", nullable = false, length = 20)
    private WorkplaceType workplaceType;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    /** The optional district inside the city (free text — no geo dependency; the reviews-pattern self-containment). */
    @Column(name = "district", length = 100)
    private String district;

    /** The optional salary floor (cents, SAR by default) — present only as the all-or-nothing block. */
    @Min(0)
    @Column(name = "salary_min_cents")
    private Long salaryMinCents;

    /** The optional salary ceiling (cents) — {@code >=} the floor by the service guard and the V153 CHECK. */
    @Column(name = "salary_max_cents")
    private Long salaryMaxCents;

    /** ISO-4217 code — required exactly when either bound is present ("SAR" the platform's home currency). */
    @Column(name = "salary_currency", length = 3)
    private String salaryCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status = JobStatus.ACTIVE;

    /** The optional application deadline — past it, applying answers the conflict loudly (the service guard). */
    @Column(name = "application_deadline")
    private Instant applicationDeadline;

    protected JobListing() {
    }

    private JobListing(UUID id, UUID employerId, String title, String description,
                       EmploymentType employmentType, WorkplaceType workplaceType,
                       String city, String district, Long salaryMinCents, Long salaryMaxCents,
                       String salaryCurrency, Instant applicationDeadline) {
        this.id = id;
        this.employerId = employerId;
        this.title = title;
        this.description = description;
        this.employmentType = employmentType;
        this.workplaceType = workplaceType;
        this.city = city;
        this.district = district;
        this.salaryMinCents = salaryMinCents;
        this.salaryMaxCents = salaryMaxCents;
        this.salaryCurrency = salaryCurrency;
        this.applicationDeadline = applicationDeadline;
        this.status = JobStatus.ACTIVE;
    }

    /**
     * The posting factory: a job is born ACTIVE — the create-and-be-seen
     * journey ({@code Review.create} precedent; the board returns it from
     * the same transaction). The salary block arrives all-or-nothing or the
     * factory itself fails loudly ({@code IllegalArgumentException} — the
     * {@code Review.create} rating-floor discipline: the shape guard lives
     * with the shape, the service's bean validation ran before it).
     */
    public static JobListing create(UUID employerId, String title, String description,
                                    EmploymentType employmentType, WorkplaceType workplaceType,
                                    String city, String district,
                                    Long salaryMinCents, Long salaryMaxCents, String salaryCurrency,
                                    Instant applicationDeadline) {
        boolean hasAny = salaryMinCents != null || salaryMaxCents != null || salaryCurrency != null;
        boolean hasAll = salaryMinCents != null && salaryMaxCents != null && salaryCurrency != null;
        if (hasAny && !hasAll) {
            throw new IllegalArgumentException("The salary block is all-or-nothing: both bounds and the currency together, or none");
        }
        if (hasAll && salaryMinCents > salaryMaxCents) {
            throw new IllegalArgumentException("The salary floor cannot exceed the salary ceiling");
        }
        // The negative floor is the factory's own loud 400 (the CodeRabbit
        // round-1 root adoption): without this guard a negative value
        // rode all the way to the V153 chk_jobs_salary_block CHECK and
        // surfaced as a 500-flavored constraint violation instead of the
        // honest client error — the shape guard lives with the shape.
        if (hasAll && salaryMinCents < 0) {
            throw new IllegalArgumentException("The salary floor cannot be negative");
        }
        return new JobListing(UUID.randomUUID(), employerId, title, description, employmentType,
                workplaceType, city, district, salaryMinCents, salaryMaxCents,
                salaryCurrency, applicationDeadline);
    }

    /** The one-way close (the {@code ListingStatus} discipline) — called only by the service after its gate. */
    public void close() {
        this.status = JobStatus.CLOSED;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getEmployerId() { return employerId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public WorkplaceType getWorkplaceType() { return workplaceType; }
    public String getCity() { return city; }
    public String getDistrict() { return district; }
    public Long getSalaryMinCents() { return salaryMinCents; }
    public Long getSalaryMaxCents() { return salaryMaxCents; }
    public String getSalaryCurrency() { return salaryCurrency; }
    public JobStatus getStatus() { return status; }
    public Instant getApplicationDeadline() { return applicationDeadline; }
}
