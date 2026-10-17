package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    /** The resolution read: every ACTIVE, in-window list in the currency, deterministic order. */
    @Query("select l from PriceList l where l.active = true and l.currency = :currency"
            + " and (l.startsAt is null or l.startsAt <= :at) and (l.endsAt is null or l.endsAt > :at)"
            + " order by l.createdAt asc, l.id asc")
    List<PriceList> findActiveCovering(@Param("currency") String currency, @Param("at") Instant at);
}

interface PriceListEntryRepository extends JpaRepository<PriceListEntry, UUID> {

    /** All entries of the lists for one product (variant + product-level). */
    List<PriceListEntry> findByProductIdAndPriceListIdIn(UUID productId, List<UUID> priceListIds);

    List<PriceListEntry> findByPriceListId(UUID priceListId);
}

interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    /** Every ACTIVE, in-window promotion — the best-applicable selection reads all and picks. */
    @Query("select p from Promotion p where p.active = true"
            + " and (p.startsAt is null or p.startsAt <= :at) and (p.endsAt is null or p.endsAt > :at)"
            + " order by p.createdAt asc, p.id asc")
    List<Promotion> findActiveCovering(@Param("at") Instant at);
}

interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    Optional<Voucher> findByCode(String code);

    /** The settle-time locked read (the house findByIdForUpdate pattern). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.code = :code")
    Optional<Voucher> findByCodeForUpdate(@Param("code") String code);
}

interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, UUID> {

    Optional<VoucherRedemption> findByVoucherIdAndOrderId(UUID voucherId, UUID orderId);

    long countByVoucherId(UUID voucherId);

    long countByVoucherIdAndConsumerId(UUID voucherId, UUID consumerId);
}

interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    Optional<TaxRate> findByCountryCodeAndActiveTrue(String countryCode);
}
