package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AvailabilityServiceTest {

    @Test
    void isAvailableUsesRepositoryOverlapQuery() {
        AvailabilitySlotRepository repository = mock(AvailabilitySlotRepository.class);
        AvailabilityService service = new AvailabilityService(repository);
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T11:00:00Z");

        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(true);

        boolean available = service.isAvailable(providerId, startsAt, endsAt);

        assertThat(available).isTrue();
    }
}
