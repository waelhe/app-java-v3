package com.marketplace.catalog;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): the +1 transaction —
 * the L21 stored-aggregate pattern ({@code refreshRatingAverage} /
 * {@code findByIdForUpdate}) applied to the daily views bucket. See
 * {@link ListingViewsDaily} for why this is a locked read-modify-write
 * and not the plan's literal {@code INSERT ON CONFLICT DO UPDATE} (the
 * D-R8 live-mirror requirement; atomicity preserved by the unique
 * constraint + the row lock).
 *
 * <p><b>The insert race and its exactly-one retry:</b> two different
 * visitors hitting a never-viewed listing simultaneously both read
 * nothing (SELECT ... FOR UPDATE locks no row when none exists —
 * PostgreSQL READ COMMITTED takes no gap locks), both INSERT, and the
 * loser's flush raises {@link DataIntegrityViolationException} on the
 * UNIQUE (listing_id, view_date) constraint — PostgreSQL reports that
 * violation only after the WINNER committed (a rolled-back winner lets
 * the loser's insert proceed). The transaction holding the violated
 * insert is aborted by the database, so the retry CANNOT share it:
 * {@link ListingViewCounter} catches the violation and calls this method
 * again — a fresh transaction whose locked read now finds the winner's
 * row and increments it. One retry is therefore sufficient by database
 * semantics, not by luck.
 */
@Service
public class ListingViewsDailyService {

    private final ListingViewsDailyRepository repository;

    public ListingViewsDailyService(ListingViewsDailyRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one deduplicated view of {@code listingId} on {@code viewDate}
     * — either a new bucket row (the first view of that listing-day) or a
     * locked +1 on the existing one. The caller owns the retry-on-race
     * decision (see the class javadoc) because the retry must run in a
     * NEW transaction.
     */
    @Transactional
    public void addView(UUID listingId, LocalDate viewDate) {
        Optional<ListingViewsDaily> existing =
                repository.findByListingIdAndViewDateForUpdate(listingId, viewDate);
        if (existing.isPresent()) {
            existing.get().addView();
            return;
        }
        // saveAndFlush: the INSERT (and a lost insert race's constraint
        // violation) surface HERE, inside this transaction, before any
        // caller could mistake the row for persisted.
        repository.saveAndFlush(ListingViewsDaily.firstView(listingId, viewDate));
    }
}
