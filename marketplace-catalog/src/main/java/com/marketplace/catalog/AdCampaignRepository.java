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
 *       index V103);</li>
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

    /** The live paid promotion of a listing — at most one by the partial unique index (V103). */
    Optional<AdCampaign> findFirstByListingIdAndStatusOrderByIdAsc(UUID listingId, AdCampaignStatus status);

    /** The provider's own campaigns, newest first with the deterministic id tiebreak (L32). */
    List<AdCampaign> findByProviderIdOrderByCreatedAtDescIdDesc(UUID providerId);

    /** The owner's single campaign read — the ownership-checked surface's first hop. */
    Optional<AdCampaign> findByIdAndProviderId(UUID id, UUID providerId);

    /**
     * The billing run's candidate scan: the ids of campaigns with billable
     * work — ACTIVE with an unsettled window ending strictly before
     * {@code horizonExclusive} (the run bills COMPLETE UTC days only), or
     * ENDED-by-duration with an unsettled TAIL (the marker still at or
     * before the ends date — CodeRabbit W5 r1, adopted: without the
     * duration bound, every historical ENDED campaign stays in the scan
     * forever as a daily no-op read, and the job's work grows with the
     * campaign history).
     *
     * <p>ENDED-by-exhaustion never qualifies: {@code consume()} ends the
     * campaign exactly when the budget is fully consumed, so its invariant
     * is {@code consumed = budget} — the {@code consumed < budget} filter
     * excludes it. ENDED-by-duration after its tail settles never
     * qualifies either: the marker sits at {@code ends_date + 1}, past the
     * ends date bound. Ids only — each settle re-reads the full fresh
     * state inside its own transaction (the candidate list is advisory).
     *
     * <p><b>Native SQL by measurement:</b> the bound compares the DATE
     * marker against the ends date derived from the TIMESTAMPTZ column —
     * {@code (ends_at AT TIME ZONE 'UTC')::date} (CodeRabbit W5 r3,
     * adopted: the bare cast resolves through the PostgreSQL session
     * TimeZone, so a non-UTC session could shift the tail window by a day;
     * the AT TIME ZONE pin keeps the comparison on the same UTC daily
     * grain {@link AdBillingBatchExecutor} settles) — a cast JPQL cannot
     * express portably, and the theta form cannot either. Native queries
     * bypass the {@code @SoftDelete} automatic restriction, so the
     * predicate states {@code is_deleted = false} itself (the
     * ListingViewsDailyRepository javadoc's own law).
     */
    @Query(value = """
            select c.id from ad_campaigns c
            where c.is_deleted = false
              and (
                    (c.status = 'ACTIVE' and c.billed_through < :horizonExclusive)
                 or (c.status = 'ENDED' and c.consumed_cents < c.budget_cents
                        and c.billed_through <= (c.ends_at AT TIME ZONE 'UTC')::date)
              )
            order by c.billed_through asc, c.id asc
            """, nativeQuery = true)
    List<UUID> findBillableBefore(@Param("horizonExclusive") LocalDate horizonExclusive);
}
