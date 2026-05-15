package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Instancio.*;
import static org.mockito.Mockito.*;

class AvailabilityServiceTest {

    private final AvailabilitySlotRepository repository = mock(AvailabilitySlotRepository.class);
    private final ProviderAvailabilityRuleRepository ruleRepository = mock(ProviderAvailabilityRuleRepository.class);
    private final ProviderTimeOffRepository timeOffRepository = mock(ProviderTimeOffRepository.class);
    private final AvailabilityService service = new AvailabilityService(repository, ruleRepository, timeOffRepository);

    @Test
    void isAvailableUsesRepositoryOverlapQuery() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T11:00:00Z");

        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(true);
        when(timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(false);

        assertThat(service.isAvailable(providerId, startsAt, endsAt)).isTrue();
    }

    @Test
    void isAvailableReturnsFalseWhenSlotBooked() {
        UUID providerId = create(UUID.class);
        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(false);
        assertThat(service.isAvailable(providerId, Instant.now(), Instant.now())).isFalse();
    }

    @Test
    void isAvailableReturnsFalseWhenTimeOffConflict() {
        UUID providerId = create(UUID.class);
        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(true);
        when(timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(true);
        assertThat(service.isAvailable(providerId, Instant.now(), Instant.now())).isFalse();
    }

    @Test
    void createSlotSavesAndReturns() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T11:00:00Z");
        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(inv -> inv.getArgument(0));
        AvailabilitySlot slot = service.createSlot(providerId, startsAt, endsAt);
        assertThat(slot.getProviderId()).isEqualTo(providerId);
        assertThat(slot.getStartsAt()).isEqualTo(startsAt);
        assertThat(slot.getEndsAt()).isEqualTo(endsAt);
        assertThat(slot.isBooked()).isFalse();
    }

    @Test
    void getSlotsReturnsSlots() {
        UUID providerId = create(UUID.class);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");
        when(repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to))
                .thenReturn(List.of());
        assertThat(service.getSlots(providerId, from, to)).isEmpty();
    }

    @Test
    void createRuleSavesAndReturns() {
        UUID providerId = create(UUID.class);
        when(ruleRepository.save(any(ProviderAvailabilityRule.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderAvailabilityRule rule = service.createRule(providerId, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertThat(rule.getProviderId()).isEqualTo(providerId);
        assertThat(rule.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void createTimeOffSavesAndReturns() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant endsAt = Instant.parse("2026-01-02T00:00:00Z");
        when(timeOffRepository.save(any(ProviderTimeOff.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderTimeOff timeOff = service.createTimeOff(providerId, startsAt, endsAt);
        assertThat(timeOff.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void availabilitySlotOpenCreatesUnbookedSlot() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T11:00:00Z");
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, startsAt, endsAt);
        assertThat(slot.getProviderId()).isEqualTo(providerId);
        assertThat(slot.getStartsAt()).isEqualTo(startsAt);
        assertThat(slot.getEndsAt()).isEqualTo(endsAt);
        assertThat(slot.isBooked()).isFalse();
        assertThat(slot.getId()).isNotNull();
    }
}
