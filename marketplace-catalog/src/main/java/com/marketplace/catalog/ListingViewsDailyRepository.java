package com.marketplace.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marketplace.shared.api.ListingViewStats;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): the daily views
 * aggregate's repository. Two operations:
 *
 * <ol>
 *   <li>the pessimistic-locked bucket read the increment path uses — the
 *       L21 stored-aggregate pattern ({@code findByIdForUpdate});</li>
 *   <li>the provider's windowed per-listing totals (the
 *       {@link com.marketplace.shared.api.ListingViewsStatsPort} read).</li>
 * </ol>
 *
 * <p>Soft delete: both entities extend the Hibernate 7 {@code @SoftDelete}
 * base — Hibernate appends the {@code is_deleted = false} predicate to
 * these queries automatically, including the ad-hoc join below; the
 * integration guard seeds a soft-deleted row and proves it is excluded.
 */
public interface ListingViewsDailyRepository extends JpaRepository<ListingViewsDaily, UUID> {

    /**
     * The locked bucket read: concurrent {@code +1}s on the same
     * (listing, day) serialize here — the second transaction reads the
     * committed count after the first releases the row, so no update is
     * lost to the optimistic version instead of being retried.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ListingViewsDaily v"
            + " where v.listingId = :listingId and v.viewDate = :viewDate")
    Optional<ListingViewsDaily> findByListingIdAndViewDateForUpdate(
            @Param("listingId") UUID listingId, @Param("viewDate") LocalDate viewDate);

    /**
     * The provider's per-listing view totals for the window since
     * {@code sinceInclusive} (UTC days, inclusive start, through today) —
     * the {@code ListingViewsStatsPort} read. The join is the classic
     * JPA-spec theta form ({@code FROM a, b WHERE a.x = b.y}) because the
     * listing reference is a plain UUID column, not an association (the
     * V52 discipline) — no association to join, and the theta form needs
     * no provider-specific extension. It rides the UNIQUE
     * (listing_id, view_date) btree on this side and provider_listings'
     * provider_id index on the other; the never-viewed listing is absent
     * (the honest empty answer), the order is views DESC with the listing
     * id as the deterministic tiebreak (L32). Soft delete: Hibernate's
     * {@code @SoftDelete} appends the {@code is_deleted = false}
     * predicate for BOTH roots (the entity's own query restriction —
     * the integration guard seeds a soft-deleted bucket row AND a
     * soft-deleted listing to pin both sides).
     */
    @Query("""
            select new com.marketplace.shared.api.ListingViewStats(
                v.listingId, l.title, sum(v.viewCount))
            from ListingViewsDaily v, ProviderListing l
            where v.listingId = l.id
              and l.providerId = :providerUserId
              and v.viewDate >= :sinceInclusive
            group by v.listingId, l.title
            order by sum(v.viewCount) desc, v.listingId asc
            """)
    List<ListingViewStats> sumViewTotalsForProviderSince(
            @Param("providerUserId") UUID providerUserId,
            @Param("sinceInclusive") LocalDate sinceInclusive);

    /**
     * W5 (yelp-level plan §5 — the ads & billing wave, G24): the billing
     * window's frozen impression count — the deduplicated views of ONE
     * listing over {@code [windowStart, windowEnd)} in UTC days. This is
     * the plan's «ظهور من listing_views_daily القائم»: the ad campaign
     * bills the impressions the platform already counts, no second
     * counting path exists. The absent listing-day contributes nothing
     * (COALESCE 0 at the caller — the honest empty answer).
     */
    @Query("select coalesce(sum(v.viewCount), 0) from ListingViewsDaily v"
            + " where v.listingId = :listingId"
            + " and v.viewDate >= :windowStart and v.viewDate < :windowEnd")
    long sumViewsForListingBetween(@Param("listingId") UUID listingId,
                                   @Param("windowStart") LocalDate windowStart,
                                   @Param("windowEnd") LocalDate windowEnd);
}
