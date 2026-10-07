package com.marketplace.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * B-12 (compliance plan C.2): the listing repository — the house's mixed
 * discipline (derived queries where the shape fits, a declared
 * {@code @Query} where the optional-filter board needs null guards — the
 * {@code ReviewRepository}/{@code ListingLeadRepository} precedent), every
 * read riding the BaseEntity {@code @SoftDelete} filter (soft-deleted
 * rows are invisible by construction, Data JPA's own mechanism).
 */
public interface JobListingRepository extends JpaRepository<JobListing, UUID> {

    /**
     * The public board: the status dimension (ACTIVE by default, CLOSED
     * reachable for the honest history) with every filter optional — null
     * drops the predicate, the declared-query null-guard discipline. The
     * service passes the sort ({@code created_at DESC, id DESC}: the
     * stable-order L32/D-N5 lesson) so the tiebreak is always explicit,
     * never the database's whim.
     */
    @Query("""
            select j from JobListing j
            where j.status = :status
              and (:city is null or j.city = :city)
              and (:employmentType is null or j.employmentType = :employmentType)
              and (:workplaceType is null or j.workplaceType = :workplaceType)
            """)
    Page<JobListing> searchBoard(@Param("status") JobStatus status,
                                 @Param("city") String city,
                                 @Param("employmentType") EmploymentType employmentType,
                                 @Param("workplaceType") WorkplaceType workplaceType,
                                 Pageable pageable);

    /** The employer's own management list — every status, their posts only. */
    Page<JobListing> findByEmployerIdOrderByCreatedAtDescIdDesc(UUID employerId, Pageable pageable);
}
