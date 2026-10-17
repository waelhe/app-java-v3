package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * V223 (the Saleor fit-gap study's Phase 3): the voucher redemptions'
 * persistence port — public and in its own file for the same
 * proxy-contract reason as {@link PriceListRepository}'s header documents.
 */
public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, UUID> {

    Optional<VoucherRedemption> findByVoucherIdAndOrderId(UUID voucherId, UUID orderId);

    long countByVoucherId(UUID voucherId);

    long countByVoucherIdAndConsumerId(UUID voucherId, UUID consumerId);
}
