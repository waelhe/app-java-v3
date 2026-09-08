package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

public interface ListingWeekendRuleRepository
        extends JpaRepository<ListingWeekendRule, UUID>, RevisionRepository<ListingWeekendRule, UUID, Integer> {

    /** L26: the single weekend rule of a listing (unique constraint, V41). */
    Optional<ListingWeekendRule> findByListingId(UUID listingId);
}
