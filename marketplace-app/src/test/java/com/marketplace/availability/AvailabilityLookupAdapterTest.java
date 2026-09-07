package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityLookupPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * L27 (feature-expansion roadmap §5): the availability module's
 * {@code AvailabilityLookupPort} implementation is a read-only delegation
 * to the bulk predicate — this pins the wiring and the pass-through
 * contract. The predicate semantics themselves (strict overlap, booked
 * exclusion, time-off exclusion) run against real PostgreSQL in
 * {@code SearchWindowFilterIntegrationTest}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { AvailabilityLookupAdapter.class })
class AvailabilityLookupAdapterTest {

    @Autowired
    private AvailabilityLookupPort availabilityLookupPort;

    @MockitoBean
    private AvailabilitySlotRepository slotRepository;

    @Test
    void delegatesToTheBulkPredicate_verbatim() {
        Instant startsAt = Instant.parse("2026-09-25T10:00:00Z");
        Instant endsAt = Instant.parse("2026-09-28T10:00:00Z");
        Set<UUID> answer = Set.of(UUID.randomUUID());
        when(slotRepository.findAvailableProviderIds(startsAt, endsAt)).thenReturn(answer);

        Set<UUID> result = availabilityLookupPort.findAvailableProviderIds(startsAt, endsAt);

        assertThat(result).isEqualTo(answer);
        verify(slotRepository).findAvailableProviderIds(startsAt, endsAt);
        verifyNoMoreInteractions(slotRepository);
    }
}
