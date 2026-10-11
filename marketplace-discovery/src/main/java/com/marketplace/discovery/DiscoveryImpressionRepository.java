package com.marketplace.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20 — AC-20-03): the impression ledger's
 * repository — the write path is the native bridge ONLY ({@link
 * #insertOnce}); the ledger rows are born complete and never mutated, so
 * no derived read, no delete and no save path exists here by design (the
 * V93 {@code provider_follow_alerts} bridge discipline: an operational
 * ledger with no domain lifecycle).
 */
public interface DiscoveryImpressionRepository extends JpaRepository<DiscoveryImpression, UUID> {

    /**
     * The idempotency ledger insert — native {@code INSERT ... ON CONFLICT
     * DO NOTHING} (the V93 {@code provider_follow_alerts} bridge, itself
     * the V54 {@code saved_search_matches} precedent): the skip is the
     * affected-row count — a repeated (user, row, source, day) impression
     * returns 0 and is a quiet no-op, NEVER a transaction-aborting 23505.
     * The conflict key is exactly V176's {@code uq_discovery_impressions_once}
     * index shape.
     *
     * <p>{@code @Transactional} sits on the method itself so the insert
     * owns its own short transaction: the caller (the discovery service's
     * impression recording) deliberately runs OUTSIDE any surrounding
     * transaction and catches any failure — the browse experience never
     * fails on an impression-recording hiccup (the 204-always contract).</p>
     *
     * @return 1 when the impression was freshly recorded, 0 when the pair
     *         was already recorded for the day (the documented skip)
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO discovery_impressions
                   (id, user_id, row_type, source_type, source_id, impression_day)
            VALUES (:id, :userId, :rowType, :sourceType, :sourceId, :impressionDay)
            ON CONFLICT (user_id, row_type, source_type, source_id, impression_day) DO NOTHING
            """, nativeQuery = true)
    int insertOnce(@Param("id") UUID id,
                   @Param("userId") UUID userId,
                   @Param("rowType") String rowType,
                   @Param("sourceType") String sourceType,
                   @Param("sourceId") UUID sourceId,
                   @Param("impressionDay") LocalDate impressionDay);
}
