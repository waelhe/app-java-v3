package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class BookingInfoTest {

    private final UUID providerId = UUID.randomUUID();
    private final UUID consumerId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test
    void constructor_rejectsNullPriceCents() {
        assertThatThrownBy(() -> new BookingInfo(providerId, consumerId, "OPEN", null, "USD", now, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid price");
    }

    @Test
    void constructor_rejectsNonPositivePriceCents() {
        assertThatThrownBy(() -> new BookingInfo(providerId, consumerId, "OPEN", 0L, "USD", now, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid price");
    }

    @Test
    void constructor_rejectsNullCurrency() {
        assertThatThrownBy(() -> new BookingInfo(providerId, consumerId, "OPEN", 1000L, null, now, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid currency");
    }

    @Test
    void constructor_rejectsBlankCurrency() {
        assertThatThrownBy(() -> new BookingInfo(providerId, consumerId, "OPEN", 1000L, "  ", now, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid currency");
    }

    @Test
    void constructor_success() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        assertThat(info.providerId()).isEqualTo(providerId);
        assertThat(info.consumerId()).isEqualTo(consumerId);
        assertThat(info.status()).isEqualTo("OPEN");
    }

    @Test
    void requireStatus_throwsWhenWrong() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        assertThatThrownBy(() -> info.requireStatus("CONFIRMED", "cancel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    void requireStatus_success() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        info.requireStatus("OPEN", "cancel");
    }

    @Test
    void requireStatusNot_throwsWhenMatch() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        assertThatThrownBy(() -> info.requireStatusNot("OPEN", "cancel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    void requireStatusNot_success() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        info.requireStatusNot("CONFIRMED", "cancel");
    }

    @Test
    void requireParticipant_throwsForNonParticipant() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> info.requireParticipant(stranger))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("participant");
    }

    @Test
    void requireParticipant_success_asConsumer() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        info.requireParticipant(consumerId);
    }

    @Test
    void requireParticipant_success_asProvider() {
        var info = new BookingInfo(providerId, consumerId, "OPEN", 1000L, "USD", now, now);
        info.requireParticipant(providerId);
    }
}
