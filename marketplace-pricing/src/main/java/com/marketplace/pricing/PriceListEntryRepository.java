package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * V223 (the Saleor fit-gap study's Phase 3): the price-list entries'
 * persistence port — public and in its own file for the same proxy-contract
 * reason as {@link PriceListRepository}'s header documents.
 */
public interface PriceListEntryRepository extends JpaRepository<PriceListEntry, UUID> {

    /** All entries of the lists for one product (variant + product-level). */
    List<PriceListEntry> findByProductIdAndPriceListIdIn(UUID productId, List<UUID> priceListIds);

    List<PriceListEntry> findByPriceListId(UUID priceListId);
}
