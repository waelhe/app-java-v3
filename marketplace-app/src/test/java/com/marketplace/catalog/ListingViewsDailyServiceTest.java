package com.marketplace.catalog;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L40 (realestate systems plan §5 — view analytics): the +1 transaction's
 * two paths — the locked increment on the existing bucket, and the
 * first-view insert (whose unique-constraint race the CATCHER retries;
 * here the repository is a mock, so the constraint violation itself is
 * the counter test's insert-race case). The real pessimistic lock, the
 * Envers revisions and the constraint are the integration test's.
 */
class ListingViewsDailyServiceTest {

    private final ListingViewsDailyRepository repository = mock(ListingViewsDailyRepository.class);
    private final ListingViewsDailyService service = new ListingViewsDailyService(repository);

    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Test
    void existingBucket_isIncrementedInPlace() {
        ListingViewsDaily row = ListingViewsDaily.firstView(LISTING_ID, TODAY);
        long before = row.getViewCount();
        when(repository.findByListingIdAndViewDateForUpdate(LISTING_ID, TODAY))
                .thenReturn(Optional.of(row));

        service.addView(LISTING_ID, TODAY);

        assertThat(row.getViewCount()).isEqualTo(before + 1);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void firstView_insertsTheBucketWithCountOne() {
        when(repository.findByListingIdAndViewDateForUpdate(LISTING_ID, TODAY))
                .thenReturn(Optional.empty());

        service.addView(LISTING_ID, TODAY);

        ArgumentCaptor<ListingViewsDaily> saved = ArgumentCaptor.forClass(ListingViewsDaily.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getListingId()).isEqualTo(LISTING_ID);
        assertThat(saved.getValue().getViewDate()).isEqualTo(TODAY);
        assertThat(saved.getValue().getViewCount()).isEqualTo(1L);
        assertThat(saved.getValue().getId()).isNotNull(); // application-assigned (the D-I7 lesson)
    }

    @Test
    void lostInsertRace_propagatesForTheCallerToRetryInANewTransaction() {
        // The service does NOT swallow the violation: PostgreSQL aborted
        // this transaction, and the retry must run in a NEW one — that
        // decision belongs to the caller (the counter), per the class
        // javadoc's contract.
        when(repository.findByListingIdAndViewDateForUpdate(LISTING_ID, TODAY))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "uk_listing_views_daily_listing_date"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.addView(LISTING_ID, TODAY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
