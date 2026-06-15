package com.marketplace.availability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.springframework.modulith.moments.DayHasPassed;

class AvailabilityServiceTest {

    private final AvailabilitySlotRepository repository = mock(AvailabilitySlotRepository.class);
    private final ProviderAvailabilityRuleRepository ruleRepository = mock(ProviderAvailabilityRuleRepository.class);
    private final ProviderTimeOffRepository timeOffRepository = mock(ProviderTimeOffRepository.class);
    private AvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new AvailabilityService(repository, ruleRepository, timeOffRepository);
    }

    @Test
    void createSlotSavesAndReturnsSlot() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");

        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(inv -> inv.getArgument(0));

        AvailabilitySlot slot = service.createSlot(providerId, startsAt, endsAt);

        assertThat(slot.getProviderId()).isEqualTo(providerId);
        assertThat(slot.getStartsAt()).isEqualTo(startsAt);
        assertThat(slot.getEndsAt()).isEqualTo(endsAt);
        assertThat(slot.isBooked()).isFalse();
        verify(repository).save(any(AvailabilitySlot.class));
    }

    @Test
    void getSlotsDelegatesToRepository() {
        UUID providerId = create(UUID.class);
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");
        List<AvailabilitySlot> expected = List.of(mock(AvailabilitySlot.class));

        when(repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to))
                .thenReturn(expected);

        List<AvailabilitySlot> result = service.getSlots(providerId, from, to);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void isAvailableReturnsTrueWhenSlotExistsAndNoTimeOff() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T11:00:00Z");

        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(true);
        when(timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(false);

        boolean available = service.isAvailable(providerId, startsAt, endsAt);

        assertThat(available).isTrue();
    }

    @Test
    void isAvailableReturnsFalseWhenSlotDoesNotExist() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T11:00:00Z");

        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(false);

        boolean available = service.isAvailable(providerId, startsAt, endsAt);

        assertThat(available).isFalse();
    }

    @Test
    void isAvailableReturnsFalseWhenTimeOffConflicts() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T11:00:00Z");

        when(repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(true);
        when(timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt))
                .thenReturn(true);

        boolean available = service.isAvailable(providerId, startsAt, endsAt);

        assertThat(available).isFalse();
    }

    @Test
    void createRuleSavesAndReturnsRule() {
        UUID providerId = create(UUID.class);
        DayOfWeek dayOfWeek = DayOfWeek.MONDAY;
        LocalTime startTime = LocalTime.of(9, 0);
        LocalTime endTime = LocalTime.of(17, 0);

        when(ruleRepository.save(any(ProviderAvailabilityRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderAvailabilityRule rule = service.createRule(providerId, dayOfWeek, startTime, endTime);

        assertThat(rule.getId()).isNotNull();
        verify(ruleRepository).save(any(ProviderAvailabilityRule.class));
    }

    @Test
    void createTimeOffSavesAndReturnsTimeOff() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant endsAt = Instant.parse("2026-07-07T00:00:00Z");

        when(timeOffRepository.save(any(ProviderTimeOff.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderTimeOff timeOff = service.createTimeOff(providerId, startsAt, endsAt);

        assertThat(timeOff.getId()).isNotNull();
        verify(timeOffRepository).save(any(ProviderTimeOff.class));
    }

    @Test
    void onDayHasPassed_generatesSlotsFromRules() {
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, today, LocalTime.of(9, 0), LocalTime.of(17, 0));
        DayHasPassed event = mock(DayHasPassed.class);

        when(ruleRepository.findByDayOfWeek(today)).thenReturn(List.of(rule));
        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onDayHasPassed(event);

        verify(ruleRepository).findByDayOfWeek(today);
        verify(repository).save(any(AvailabilitySlot.class));
    }

    @Test
    void onDayHasPassed_skipsWhenSlotAlreadyExists() {
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, today, LocalTime.of(9, 0), LocalTime.of(17, 0));
        DayHasPassed event = mock(DayHasPassed.class);

        when(ruleRepository.findByDayOfWeek(today)).thenReturn(List.of(rule));
        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(any(), any(), any()))
                .thenReturn(Optional.of(mock(AvailabilitySlot.class)));

        service.onDayHasPassed(event);

        verify(ruleRepository).findByDayOfWeek(today);
        verify(repository, never()).save(any(AvailabilitySlot.class));
    }

    @Test
    void onDayHasPassed_doesNothingWhenNoRules() {
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        DayHasPassed event = mock(DayHasPassed.class);

        when(ruleRepository.findByDayOfWeek(today)).thenReturn(List.of());

        service.onDayHasPassed(event);

        verify(ruleRepository).findByDayOfWeek(today);
        verifyNoInteractions(repository);
    }
}
