package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Instancio.*;
import static org.mockito.Mockito.*;

class AvailabilityServiceTest {

    @Test
    void isAvailableUsesRepositoryOverlapQuery() {
        AvailabilitySlotRepository repository = mock(AvailabilitySlotRepository.class);
        ProviderAvailabilityRuleRepository ruleRepository = mock(ProviderAvailabilityRuleRepository.class);
        ProviderTimeOffRepository timeOffRepository = mock(ProviderTimeOffRepository.class);
        AvailabilityService service = new AvailabilityService(repository, ruleRepository, timeOffRepository);
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T11:00:00Z");

        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(true);
        when(timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(false);

        boolean available = service.isAvailable(providerId, startsAt, endsAt);

        assertThat(available).isTrue();
    }
}
