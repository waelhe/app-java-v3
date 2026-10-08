package com.marketplace.catalog;

import com.marketplace.shared.resilience.InsertRaceRetryConfiguration;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the click's +1
 * transaction — {@code ListingViewsDailyService}'s contract verbatim (the
 * L21 locked read-modify-write, the insert race's framework-managed retry
 * in a NEW transaction via {@link InsertRaceRetryConfiguration} —
 * PostgreSQL aborts the transaction holding the violated insert, so the
 * retry cannot share it; the loser's violation surfaces only after the
 * winner committed).
 */
@Service
public class AdClicksDailyService {

    private final AdClickDailyRepository repository;

    public AdClicksDailyService(AdClickDailyRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one deduplicated click of {@code campaignId} on
     * {@code clickDate} — either a new bucket row (the first click of
     * that campaign-day) or a locked +1 on the existing one. A lost
     * insert race is retried by the framework-managed {@code @Retry}
     * below — in a NEW transaction, which is why {@code REQUIRES_NEW}
     * carries the per-attempt boundary.
     */
    @Retry(name = InsertRaceRetryConfiguration.INSERT_RACE_RETRY)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addClick(UUID campaignId, LocalDate clickDate) {
        Optional<AdClickDaily> existing =
                repository.findByCampaignIdAndClickDateForUpdate(campaignId, clickDate);
        if (existing.isPresent()) {
            existing.get().addClick();
            return;
        }
        // saveAndFlush: the INSERT (and a lost insert race's constraint
        // violation) surface HERE, inside this transaction, before any
        // caller could mistake the row for persisted.
        repository.saveAndFlush(AdClickDaily.firstClick(campaignId, clickDate));
    }
}
