package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.shared.api.ReviewExportEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewExportAdapterTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewExportAdapter adapter = new ReviewExportAdapter(reviewRepository);

    @Test
    void forwardReviewCarriesTheReviewedProviderAsTheOpaqueCounterparty() {
        UUID me = UUID.randomUUID();
        UUID reviewedProviderUser = UUID.randomUUID();
        Review forward = Review.create(UUID.randomUUID(), me,
                reviewedProviderUser, 5, "punctual and thorough");
        when(reviewRepository.findAllByReviewerIdOrderByCreatedAtAsc(me))
                .thenReturn(List.of(forward));

        List<ReviewExportEntry> entries = adapter.exportForAuthor(me);

        assertEquals(1, entries.size());
        ReviewExportEntry entry = entries.get(0);
        assertEquals("CONSUMER_TO_PROVIDER", entry.direction());
        assertEquals(reviewedProviderUser, entry.reviewedProviderUserId());
        assertNull(entry.reviewedConsumerUserId());
        assertEquals(5, entry.rating());
        assertEquals("punctual and thorough", entry.comment());
        assertEquals(forward.getBookingId(), entry.bookingId());
    }

    @Test
    void reverseReviewCarriesTheReviewedConsumerAsTheOpaqueCounterparty() {
        UUID me = UUID.randomUUID();
        UUID myProviderUser = UUID.randomUUID();
        UUID reviewedConsumer = UUID.randomUUID();
        Review reverse = Review.createReverse(UUID.randomUUID(), me,
                myProviderUser, reviewedConsumer, 4, "easy to work with");
        // The counterparty's reply never applies to his own authored review,
        // and the reply the counterparty left on his forward review is
        // excluded by the mapping (the entry record has no reply field at
        // all — compile-time guarantee).
        when(reviewRepository.findAllByReviewerIdOrderByCreatedAtAsc(me))
                .thenReturn(List.of(reverse));

        List<ReviewExportEntry> entries = adapter.exportForAuthor(me);

        assertEquals(1, entries.size());
        ReviewExportEntry entry = entries.get(0);
        assertEquals("PROVIDER_TO_CONSUMER", entry.direction());
        assertEquals(reviewedConsumer, entry.reviewedConsumerUserId());
        assertNull(entry.reviewedProviderUserId());
        assertEquals(4, entry.rating());
    }

    @Test
    void theEntryContractCarriesHisAuthoredFieldsOnly() {
        // No reply/repliedAt (the counterparty's authored content), no audit
        // columns — the contract shape is asserted so a field addition must
        // consciously re-open this test.
        assertEquals(9, ReviewExportEntry.class.getRecordComponents().length);
    }
}
