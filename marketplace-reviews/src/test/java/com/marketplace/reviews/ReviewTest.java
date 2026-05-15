package com.marketplace.reviews;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

class ReviewTest {

    @Test
    void shouldCreateReview() {
        UUID bookingId = Instancio.create(UUID.class);
        UUID reviewerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        Review review = Instancio.of(Review.class)
                .set(field(Review::getBookingId), bookingId)
                .set(field(Review::getReviewerId), reviewerId)
                .set(field(Review::getProviderId), providerId)
                .set(field(Review::getRating), 5)
                .set(field(Review::getComment), "Great service")
                .create();

        assertThat(review.getBookingId()).isEqualTo(bookingId);
        assertThat(review.getReviewerId()).isEqualTo(reviewerId);
        assertThat(review.getProviderId()).isEqualTo(providerId);
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getComment()).isEqualTo("Great service");
        assertThat(review.getId()).isNotNull();
    }

    @Test
    void shouldRejectRatingBelowOne() {
        assertThatThrownBy(() -> Review.create(Instancio.create(UUID.class), Instancio.create(UUID.class), Instancio.create(UUID.class), 0, "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rating must be between 1 and 5");
    }

    @Test
    void shouldRejectRatingAboveFive() {
        assertThatThrownBy(() -> Review.create(Instancio.create(UUID.class), Instancio.create(UUID.class), Instancio.create(UUID.class), 6, "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rating must be between 1 and 5");
    }

    @Test
    void shouldUpdateReview() {
        Review review = Instancio.of(Review.class)
                .set(field(Review::getRating), 3)
                .set(field(Review::getComment), "OK")
                .create();
        review.update(4, "Better after update");

        assertThat(review.getRating()).isEqualTo(4);
        assertThat(review.getComment()).isEqualTo("Better after update");
    }

    @Test
    void shouldRejectUpdateWithInvalidRating() {
        Review review = Instancio.of(Review.class)
                .set(field(Review::getRating), 3)
                .set(field(Review::getComment), "OK")
                .create();
        assertThatThrownBy(() -> review.update(7, "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rating must be between 1 and 5");
    }

    @Test
    void shouldAllowNullComment() {
        Review review = Instancio.of(Review.class)
                .set(field(Review::getRating), 4)
                .set(field(Review::getComment), null)
                .create();

        assertThat(review.getComment()).isNull();
    }

    @Test
    void shouldGenerateUniqueIds() {
        Review r1 = Instancio.of(Review.class)
                .set(field(Review::getRating), 5)
                .set(field(Review::getComment), "a")
                .create();
        Review r2 = Instancio.of(Review.class)
                .set(field(Review::getRating), 4)
                .set(field(Review::getComment), "b")
                .create();

        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }
}
