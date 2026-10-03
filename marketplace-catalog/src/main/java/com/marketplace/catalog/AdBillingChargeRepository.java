package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the immutable
 * charge record's repository. INSERT and READ only — no update surface
 * exists anywhere (the freeze is structural: the {@link AdBillingCharge}
 * javadoc). Soft delete rides the Hibernate 7 {@code @SoftDelete}
 * automatic predicate like every house repository.
 */
public interface AdBillingChargeRepository extends JpaRepository<AdBillingCharge, UUID> {

    /**
     * The campaign's immutable billing history — newest window first with
     * the id as the deterministic tiebreak (the L32 total-order law; two
     * windows of one campaign can never share a windowStart, but the
     * order stays deterministic regardless).
     */
    java.util.List<AdBillingCharge> findByCampaignIdOrderByWindowStartDescIdDesc(UUID campaignId);
}
