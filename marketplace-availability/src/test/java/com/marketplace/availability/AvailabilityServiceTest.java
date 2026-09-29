package com.marketplace.availability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.marketplace.shared.api.ConflictException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.moments.DayHasPassed;

import java.util.List;

import static org.mockito.Mockito.doThrow;

class AvailabilityServiceTest {

    private final AvailabilitySlotRepository repository = mock(AvailabilitySlotRepository.class);
    private final ProviderAvailabilityRuleRepository ruleRepository = mock(ProviderAvailabilityRuleRepository.class);
    private final ProviderTimeOffRepository timeOffRepository = mock(ProviderTimeOffRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    /**
     * CodeRabbit round 1 on PR #471: the per-rule generation unit — a
     * separate bean with its own REQUIRES_NEW transaction.
     */
    private final AvailabilitySlotGenerator slotGenerator = mock(AvailabilitySlotGenerator.class);
    private AvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new AvailabilityService(repository, ruleRepository, timeOffRepository, eventPublisher,
                slotGenerator);
    }

    @Test
    void createSlotSavesAndReturnsSlot() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");

        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(inv -> inv.getArgument(0));

        AvailabilitySlotResponse slot = service.createSlot(providerId, startsAt, endsAt);

        assertThat(slot.providerId()).isEqualTo(providerId);
        assertThat(slot.startsAt()).isEqualTo(startsAt);
        assertThat(slot.endsAt()).isEqualTo(endsAt);
        assertThat(slot.booked()).isFalse();
        verify(repository).save(any(AvailabilitySlot.class));
    }

    @Test
    void getSlotsDelegatesToRepository() {
        UUID providerId = create(UUID.class);
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, from, to);
        List<AvailabilitySlot> saved = List.of(slot);

        when(repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to))
                .thenReturn(saved);

        List<AvailabilitySlotResponse> result = service.getSlots(providerId, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).providerId()).isEqualTo(providerId);
        assertThat(result.get(0).startsAt()).isEqualTo(from);
        assertThat(result.get(0).endsAt()).isEqualTo(to);
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
    void hasExactAvailableSlot_returnsTrueWhenExactOpenSlotExists() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T10:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T11:00:00Z");

        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt))
                .thenReturn(Optional.of(mock(AvailabilitySlot.class)));

        boolean exact = service.hasExactAvailableSlot(providerId, startsAt, endsAt);

        assertThat(exact).isTrue();
    }

    @Test
    void hasExactAvailableSlot_returnsFalseWhenOnlySubWindowCovered() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:30:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:30:00Z");

        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt))
                .thenReturn(Optional.empty());

        boolean exact = service.hasExactAvailableSlot(providerId, startsAt, endsAt);

        assertThat(exact).isFalse();
    }

    @Test
    void createRuleSavesAndReturnsRule() {
        UUID providerId = create(UUID.class);
        DayOfWeek dayOfWeek = DayOfWeek.MONDAY;
        LocalTime startTime = LocalTime.of(9, 0);
        LocalTime endTime = LocalTime.of(17, 0);

        when(ruleRepository.save(any(ProviderAvailabilityRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderAvailabilityRuleResponse rule = service.createRule(providerId, dayOfWeek, startTime, endTime);

        assertThat(rule.id()).isNotNull();
        verify(ruleRepository).save(any(ProviderAvailabilityRule.class));
    }

    @Test
    void createTimeOffSavesAndReturnsTimeOff() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant endsAt = Instant.parse("2026-07-07T00:00:00Z");

        when(timeOffRepository.save(any(ProviderTimeOff.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderTimeOffResponse timeOff = service.createTimeOff(providerId, startsAt, endsAt);

        assertThat(timeOff.id()).isNotNull();
        verify(timeOffRepository).save(any(ProviderTimeOff.class));
    }

    @Test
    void onDayHasPassed_generatesSlotsFromRules() {
        LocalDate date = LocalDate.now();
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, date.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(17, 0));
        DayHasPassed event = DayHasPassed.of(date);

        when(ruleRepository.findByDayOfWeek(date.getDayOfWeek())).thenReturn(List.of(rule));

        service.onDayHasPassed(event);

        verify(ruleRepository, atLeastOnce()).findByDayOfWeek(date.getDayOfWeek());
        // Through the bean proxy — the REQUIRES_NEW unit runs per rule.
        verify(slotGenerator).generateFrom(rule, date);
    }

    @Test
    void onDayHasPassed_generatesSlotsForSevenDays() {
        LocalDate monday = LocalDate.of(2026, 6, 15);
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
        DayHasPassed event = DayHasPassed.of(monday);

        when(ruleRepository.findByDayOfWeek(DayOfWeek.MONDAY)).thenReturn(List.of(rule));
        when(ruleRepository.findByDayOfWeek(DayOfWeek.TUESDAY)).thenReturn(List.of());
        when(ruleRepository.findByDayOfWeek(DayOfWeek.WEDNESDAY)).thenReturn(List.of());
        when(ruleRepository.findByDayOfWeek(DayOfWeek.THURSDAY)).thenReturn(List.of());
        when(ruleRepository.findByDayOfWeek(DayOfWeek.FRIDAY)).thenReturn(List.of());
        when(ruleRepository.findByDayOfWeek(DayOfWeek.SATURDAY)).thenReturn(List.of());
        when(ruleRepository.findByDayOfWeek(DayOfWeek.SUNDAY)).thenReturn(List.of());

        service.onDayHasPassed(event);

        verify(ruleRepository, times(7)).findByDayOfWeek(any(DayOfWeek.class));
        verify(slotGenerator).generateFrom(rule, monday);
        verifyNoMoreInteractions(slotGenerator);
    }

    @Test
    void onDayHasPassed_doesNothingWhenNoRules() {
        LocalDate date = LocalDate.now();
        DayHasPassed event = DayHasPassed.of(date);

        when(ruleRepository.findByDayOfWeek(any(DayOfWeek.class))).thenReturn(List.of());

        service.onDayHasPassed(event);

        verify(ruleRepository, times(7)).findByDayOfWeek(any(DayOfWeek.class));
        verifyNoInteractions(slotGenerator);
    }

    /**
     * CodeRabbit round 1 on PR #471 (adopted): the batch survives a rule's
     * data failure — the failing rule's REQUIRES_NEW transaction already
     * rolled back on its own, the per-rule catch absorbs the exception, and
     * the NEXT rule is still handed to the generator. On the old single
     * transaction this was impossible: the poisoned transaction took the
     * whole batch down at commit, outside the catch.
     */
    @Test
    void onDayHasPassed_dataFailureAbsorbedPerRule_batchContinues() {
        LocalDate date = LocalDate.now();
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule failing = ProviderAvailabilityRule.create(providerId, date.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(10, 0));
        ProviderAvailabilityRule healthy = ProviderAvailabilityRule.create(providerId, date.getDayOfWeek(), LocalTime.of(11, 0), LocalTime.of(12, 0));
        DayHasPassed event = DayHasPassed.of(date);

        when(ruleRepository.findByDayOfWeek(date.getDayOfWeek())).thenReturn(List.of(failing, healthy));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("flush-time 23505 absorbed"))
                .when(slotGenerator).generateFrom(failing, date);

        service.onDayHasPassed(event);

        verify(slotGenerator).generateFrom(failing, date);
        verify(slotGenerator).generateFrom(healthy, date);
    }

    // ==================== R2: slot ownership (bookSlot / releaseSlot) ====================

    /**
     * R2 (comprehensive-review-ar-fix plan §4/R2): {@code bookSlot} claims the
     * open slot IN THE NAME of the confirming booking — the booked flag and
     * the owner are one mutation.
     */
    @Test
    void bookSlotClaimsTheSlotInTheBookingName_r2() {
        UUID providerId = create(UUID.class);
        UUID bookingId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, startsAt, endsAt);

        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt))
                .thenReturn(Optional.of(slot));

        service.bookSlot(providerId, startsAt, endsAt, bookingId);

        assertThat(slot.isBooked()).isTrue();
        assertThat(slot.getHeldByBookingId()).isEqualTo(bookingId);
    }

    /** R2: no open slot — the window is already held; the confirmer gets the conflict. */
    @Test
    void bookSlotThrowsConflictWhenWindowAlreadyHeld_r2() {
        UUID providerId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");

        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookSlot(providerId, startsAt, endsAt, create(UUID.class)))
                .isInstanceOf(ConflictException.class);
    }

    /** R2: the owner's release frees the window — flag and owner clear together. */
    @Test
    void releaseSlotByOwnerReleasesTheHold_r2() {
        UUID providerId = create(UUID.class);
        UUID bookingId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, startsAt, endsAt);
        slot.markBooked(bookingId);

        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(providerId, startsAt, endsAt))
                .thenReturn(Optional.of(slot));

        service.releaseSlot(providerId, startsAt, endsAt, bookingId);

        assertThat(slot.isBooked()).isFalse();
        assertThat(slot.getHeldByBookingId()).isNull();
    }

    /**
     * R2 — the review's measured finding, verbatim: the release carried by a
     * DIFFERENT booking (the PENDING sibling of the holder) is a no-op — the
     * slot a CONFIRMED booking owns stays booked, so the window can never be
     * double-booked through a sibling cancel.
     */
    @Test
    void releaseSlotByNonOwnerKeepsTheHold_r2() {
        UUID providerId = create(UUID.class);
        UUID holderBookingId = create(UUID.class);
        UUID siblingBookingId = create(UUID.class);
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, startsAt, endsAt);
        slot.markBooked(holderBookingId);

        when(repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(providerId, startsAt, endsAt))
                .thenReturn(Optional.of(slot));

        service.releaseSlot(providerId, startsAt, endsAt, siblingBookingId);

        assertThat(slot.isBooked()).as("the hold survives a non-owner cancel").isTrue();
        assertThat(slot.getHeldByBookingId()).isEqualTo(holderBookingId);
    }
}
