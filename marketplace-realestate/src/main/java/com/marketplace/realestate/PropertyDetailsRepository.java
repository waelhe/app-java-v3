package com.marketplace.realestate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PropertyDetailsRepository
        extends JpaRepository<PropertyDetails, UUID>,
        JpaSpecificationExecutor<PropertyDetails>,
        RevisionRepository<PropertyDetails, UUID, Integer> {

    /** The 1:1 lookup — listing_id is UNIQUE (V48). */
    Optional<PropertyDetails> findByListingId(UUID listingId);

    boolean existsByListingId(UUID listingId);

    /** The batch embed lookup for page aggregation (one IN query). */
    List<PropertyDetails> findByListingIdIn(Set<UUID> listingIds);
}
