package com.marketplace.institutions;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * B-13 (compliance plan C.3): the registry repository — the house's
 * mixed discipline (a declared {@code @Query} where the optional-filter
 * board needs null guards, derived queries for the fixed-shape reads —
 * the {@code ReviewRepository}/{@code ListingLeadRepository} precedent),
 * the SERVICE passing every sort (the stable-order L32 lesson), every
 * read riding the BaseEntity {@code @SoftDelete} filter.
 */
public interface InstitutionRepository extends JpaRepository<Institution, UUID> {

    /**
     * The public registry board: every verification state (the honest
     * registry — the state rides the response, the trust mark is the
     * review's verdict, never the registration's default), the type and
     * state axes optional (null drops the predicate).
     */
    @Query("""
            select i from Institution i
            where (:type is null or i.type = :type)
              and (:state is null or i.verificationState = :state)
            """)
    Page<Institution> searchBoard(@Param("type") InstitutionType type,
                                  @Param("state") InstitutionVerificationState state,
                                  Pageable pageable);

    /**
     * Stage 5 (community platform execution plan — the unified legal
     * multi-domain search): the registry's unified-search read. The SAME
     * visibility contract as the public board above (every verification
     * state — the honest registry; the soft-delete filter applies to the
     * JPQL) with the text axis over name/description and the location
     * axis optional. Deterministic (name, id) order. The unified
     * orchestrator's adapter adds no predicate and drops none.
     */
    @Query("""
            select i from Institution i
            where (:locationId is null or i.locationId = :locationId)
              and (lower(i.name) like lower(concat('%', :query, '%'))
                   or lower(coalesce(i.description, '')) like lower(concat('%', :query, '%')))
            order by i.name asc, i.id asc
            """)
    Page<Institution> searchPublicTextUnified(@Param("locationId") UUID locationId,
                                              @Param("query") String query,
                                              Pageable pageable);

    /** The representative's own registry — their managed institutions (the service passes the sort). */
    Page<Institution> findByRepresentativeId(UUID representativeId, Pageable pageable);

    /** The review queue scoped by state — the service passes the complete drain order (updatedAt ASC, id ASC). */
    Page<Institution> findByVerificationState(InstitutionVerificationState state, Pageable pageable);
}
