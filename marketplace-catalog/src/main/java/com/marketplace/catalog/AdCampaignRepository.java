package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the campaign
 * repository. The queries the three surfaces own:
 *
 * <ol>
 *   <li>the boost read — «does this listing have a live paid promotion?»
 *       (the one ACTIVE campaign, at most one by the partial unique
 *       index V100);</li>
 *   <li>the provider's own campaign list ({@code /providers/me});</li>
 *   <li>the billing run's candidate scan — unsettled windows only.</li>
 * </ol>
 *
 * <p>Soft delete: {@code AdCampaign} extends the Hibernate 7
 * {@code @SoftDelete} base — Hibernate appends the {@code is_deleted =
 * false} predicate to every query below automatically (the exact
 * contract {@code ListingViewsDailyRepository}'s javadoc documents), so
 * no method name or JPQL clause states it.</p>
 */
public interface AdCampaignRepository extends JpaRepository<AdCampaign, UUID>, JpaSpecificationExecutor<AdCampaign> {

    /** The live paid promotion of a listing — at most one by the partial unique index (V100). */
    Optional<AdCampaign> findFirstByListingIdAndStatusOrderByIdAsc(UUID listingId, AdCampaignStatus status);

    /** The provider's own campaigns, newest first with the deterministic id tiebreak (L32). */
    List<AdCampaign> findByProviderIdOrderByCreatedAtDescIdDesc(UUID providerId);

    /** The owner's single campaign read — the ownership-checked surface's first hop. */
    Optional<AdCampaign> findByIdAndProviderId(UUID id, UUID providerId);

    /**
     * The billing run's candidate scan: the ids of campaigns with an
     * unsettled window ending strictly before {@code horizonExclusive}
     * (the run bills COMPLETE UTC days only — yesterday and older).
     * {@code ENDED} campaigns ride along for their final settle: the
     * exhaustion case drops out through {@code consumed_cents = budget}
     * (the run's own remaining-budget filter), the duration case settles
     * its tail once and then the advanced marker excludes it forever
     * after. Ids only — each settle re-reads the full fresh state inside
     * its own transaction (the candidate list is advisory).
     */
    @Query("""
            select c.id from AdCampaign c
            where c.status in (com.marketplace.catalog.AdCampaignStatus.ACTIVE,
                               com.marketplace.catalog.AdCampaignStatus.ENDED)
              and c.billedThrough < :horizonExclusive
            order by c.billedThrough asc, c.id asc
            """)
    List<UUID> findBillableBefore(@Param("horizonExclusive") LocalDate horizonExclusive);
}
