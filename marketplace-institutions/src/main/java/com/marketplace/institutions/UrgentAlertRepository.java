package com.marketplace.institutions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the alerts'
 * repository — the house's mixed discipline (a declared {@code @Query}
 * where the window semantics need explicit null guards — the
 * {@link InstitutionRepository} precedent), the SERVICE passing every
 * sort (the stable-order L32 lesson), every read riding the BaseEntity
 * {@code @SoftDelete} filter.
 */
public interface UrgentAlertRepository extends JpaRepository<UrgentAlert, UUID> {

    /**
     * The live read the whole product speaks (JT-10's honesty leg, the
     * port's contract): a location's alerts that were NOT withdrawn
     * ({@code isWithdrawn = false}) and whose validity window covers
     * {@code now} — {@code valid_from <= now}, and an open end
     * ({@code valid_until IS NULL}) or a strictly-later one. Withdrawn
     * alerts answer silence HERE — the withdrawal reflects on every
     * surface that reads this seam, never by a consumer's own filter.
     *
     * <p>The order is the service's own stable key — freshest first on
     * the complete {@code (validFrom DESC, id DESC)} key (D-N5), the
     * V178 {@code idx_urgent_alerts_active} partial index's exact shape.
     * The SOURCE-state eligibility (AC-20-01 — VERIFIED only) is NOT a
     * query predicate: it lives in ONE place, the service's active-alerts
     * engine (the adapter and the public read both speak through it).</p>
     */
    @Query("""
            select a from UrgentAlert a
            where a.locationId = :locationId
              and a.withdrawn = false
              and a.validFrom <= :now
              and (a.validUntil is null or a.validUntil > :now)
            order by a.validFrom desc, a.id desc
            """)
    List<UrgentAlert> findLiveByLocation(@Param("locationId") UUID locationId,
                                         @Param("now") Instant now);
}
