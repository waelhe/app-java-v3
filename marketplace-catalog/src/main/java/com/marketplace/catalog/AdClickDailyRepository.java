package com.marketplace.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the recorded
 * click's repository — the {@code ListingViewsDailyRepository} contract
 * verbatim (the locked bucket read for the increment, the windowed sum
 * for the billing). Soft delete rides the Hibernate 7 {@code @SoftDelete}
 * automatic predicate.
 */
public interface AdClickDailyRepository extends JpaRepository<AdClickDaily, UUID> {

    /**
     * The locked bucket read: concurrent {@code +1}s on the same
     * (campaign, day) serialize here — the L21 stored-aggregate pattern
     * ({@code findByIdForUpdate}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AdClickDaily c"
            + " where c.campaignId = :campaignId and c.clickDate = :clickDate")
    Optional<AdClickDaily> findByCampaignIdAndClickDateForUpdate(
            @Param("campaignId") UUID campaignId, @Param("clickDate") LocalDate clickDate);

    /**
     * The billing window's frozen click count: the deduplicated clicks of
     * one campaign over {@code [windowStart, windowEnd)} in UTC days. The
     * absent campaign-day contributes nothing (COALESCE 0 at the caller —
     * the honest empty answer).
     */
    @Query("select coalesce(sum(c.clickCount), 0) from AdClickDaily c"
            + " where c.campaignId = :campaignId"
            + " and c.clickDate >= :windowStart and c.clickDate < :windowEnd")
    long sumClicksForCampaignBetween(@Param("campaignId") UUID campaignId,
                                     @Param("windowStart") LocalDate windowStart,
                                     @Param("windowEnd") LocalDate windowEnd);
}
