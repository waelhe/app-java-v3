package com.marketplace.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * W3 (yelp-level-plan §5 — G19): the saved-listings repository — the
 * catalog module's own house shape (JpaRepository + JpaSpecificationExecutor
 * + the Envers RevisionRepository).
 *
 * <p><b>Soft-delete scoping is structural</b> (Hibernate 7
 * {@code @SoftDelete} on {@code BaseEntity}): every derived and declared
 * query below reads the LIVE rows only — a withdrawn favorite is
 * invisible to the save gate, the list, and the results flag alike
 * (and a re-save inserts a fresh row, never resurrecting the tombstone).
 */
public interface ListingFavoriteRepository extends JpaRepository<ListingFavorite, UUID>,
        JpaSpecificationExecutor<ListingFavorite>,
        RevisionRepository<ListingFavorite, UUID, Integer> {

    /**
     * The save gate's read form (the partial unique key
     * {@code uq_listing_favorites_user_listing}): the member's live row
     * for this listing, if any — the second save of the same listing
     * answers the conflict loudly before any write.
     */
    Optional<ListingFavorite> findByUserIdAndListingId(UUID userId, UUID listingId);

    /** The member's own read: the live favorites on the page. */
    Page<ListingFavorite> findByUserId(UUID userId, Pageable pageable);

    /** The results-surface flag: does this member's live favorite exist for this listing? */
    boolean existsByUserIdAndListingId(UUID userId, UUID listingId);
}
