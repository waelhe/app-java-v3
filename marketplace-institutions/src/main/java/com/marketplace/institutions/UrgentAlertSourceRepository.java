package com.marketplace.institutions;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the delegation
 * registry's repository — the house's derived-query discipline (the
 * {@link InstitutionRepository} twin: the SERVICE passes every sort —
 * the stable-order L32 lesson), every read riding the BaseEntity
 * {@code @SoftDelete} filter.
 */
public interface UrgentAlertSourceRepository extends JpaRepository<UrgentAlertSource, UUID> {

    /**
     * The delegation review queue scoped by state — the service passes
     * the complete drain order (updatedAt ASC, id ASC — the
     * {@link InstitutionService}'s own {@code VERIFICATION_QUEUE_SORT}
     * discipline verbatim; the V178 queue index shape carries it).
     */
    Page<UrgentAlertSource> findByVerificationState(InstitutionVerificationState state, Pageable pageable);
}
