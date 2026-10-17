package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * V223 (the Saleor fit-gap study's Phase 3): the promotions' persistence
 * port — public and in its own file for the same proxy-contract reason as
 * {@link PriceListRepository}'s header documents.
 */
public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    /** Every ACTIVE, in-window promotion — the best-applicable selection reads all and picks. */
    @Query("select p from Promotion p where p.active = true"
            + " and (p.startsAt is null or p.startsAt <= :at) and (p.endsAt is null or p.endsAt > :at)"
            + " order by p.createdAt asc, p.id asc")
    List<Promotion> findActiveCovering(@Param("at") Instant at);
}
