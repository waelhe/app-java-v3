package com.marketplace.availability;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AvailabilitySlotGeneratorTest {

    private final AvailabilitySlotRepository repository = mock(AvailabilitySlotRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private AvailabilitySlotGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new AvailabilitySlotGenerator(repository, eventPublisher);
    }

    @Test
    void generateFrom_savesOpenSlotWhenWindowAbsent() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId,
                date.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(17, 0));
        when(repository.existsByProviderIdAndStartsAtAndEndsAt(providerId,
                date.atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC),
                date.atTime(LocalTime.of(17, 0)).toInstant(ZoneOffset.UTC)))
                .thenReturn(false);
        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(inv -> inv.getArgument(0));

        generator.generateFrom(rule, date);

        verify(repository).save(argThat((AvailabilitySlot s) -> !s.isBooked()
                && s.getProviderId().equals(providerId)));
        // The generation write evicts the availability-dependent caches through
        // the AFTER_COMMIT relay — same freshness contract as every other write.
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    /**
     * R3 (comprehensive-review-ar-fix plan §4/R3 — the review's measured
     * finding): the existence probe must see a BOOKED slot too — the old
     * {@code booked = false} probe made the held row invisible and the
     * generator inserted a fresh open duplicate of the same window. The stub
     * answers the booked-blind predicate {@code existsByProviderIdAndStartsAtAndEndsAt}.
     */
    @Test
    void generateFrom_skipsWhenWindowAlreadyBooked_r3() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId,
                date.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(17, 0));
        // The R3 predicate — existence regardless of booked. A true answer
        // models the BOOKED row the old probe missed.
        when(repository.existsByProviderIdAndStartsAtAndEndsAt(any(), any(), any()))
                .thenReturn(true);

        generator.generateFrom(rule, date);

        verify(repository, never()).save(any(AvailabilitySlot.class));
        verifyNoInteractions(eventPublisher);
        // The discriminator against the old code: the booked-blind probe
        // (findFirstBy...BookedFalse) must never be consulted — on the old
        // code it answered Optional.empty (Mockito default) and the generator
        // SAVED the duplicate this test forbids.
        verify(repository, never()).findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(any(), any(), any());
    }

    /** Plain skip: an OPEN window already exists — no duplicate either. */
    @Test
    void generateFrom_skipsWhenWindowAlreadyOpen() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId,
                date.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(17, 0));
        when(repository.existsByProviderIdAndStartsAtAndEndsAt(any(), any(), any()))
                .thenReturn(true);

        generator.generateFrom(rule, date);

        verify(repository, never()).save(any(AvailabilitySlot.class));
    }

    /**
     * The generated slot lands on the rule's exact UTC window — the same
     * instants the existence probe consulted (a window mismatch between
     * probe and insert would defeat the dedup).
     */
    @Test
    void generateFrom_usesTheRulesExactUtcWindow() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        UUID providerId = create(UUID.class);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId,
                date.getDayOfWeek(), LocalTime.of(8, 30), LocalTime.of(12, 45));
        when(repository.existsByProviderIdAndStartsAtAndEndsAt(any(), any(), any())).thenReturn(false);
        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(inv -> inv.getArgument(0));

        generator.generateFrom(rule, date);

        var expectedStart = date.atTime(LocalTime.of(8, 30)).toInstant(ZoneOffset.UTC);
        var expectedEnd = date.atTime(LocalTime.of(12, 45)).toInstant(ZoneOffset.UTC);
        verify(repository).existsByProviderIdAndStartsAtAndEndsAt(providerId, expectedStart, expectedEnd);
        verify(repository).save(argThat((AvailabilitySlot s) ->
                s.getStartsAt().equals(expectedStart) && s.getEndsAt().equals(expectedEnd)));
    }
}
