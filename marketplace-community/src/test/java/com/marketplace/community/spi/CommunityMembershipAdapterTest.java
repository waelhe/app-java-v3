package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodMembership;
import com.marketplace.community.NeighborhoodMembershipRepository;
import com.marketplace.shared.api.CommunityMembershipPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JT-20 — the membership adapter's scope resolution, unit-pinned on the
 * {@code MediaLookupAdapterTest} delegation shape (plain Mockito, no
 * Spring): the active-membership read is the services' own 403-gate
 * query verbatim — Hibernate's {@code @SoftDelete} filter hides left
 * memberships from it, so the visible row IS the ACTIVE one (the
 * integration test proves that against the real schema).
 */
class CommunityMembershipAdapterTest {

    private static final Instant FIXED = Instant.parse("2026-09-17T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    private final NeighborhoodMembershipRepository repository =
            mock(NeighborhoodMembershipRepository.class);
    private final CommunityMembershipPort port = new CommunityMembershipAdapter(repository);

    @Test
    void resolvesTheActiveMembershipsNeighborhood() {
        UUID userId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        when(repository.findByUserId(userId))
                .thenReturn(Optional.of(NeighborhoodMembership.join(userId, locationId, CLOCK)));

        assertEquals(Optional.of(locationId), port.getActiveNeighborhoodId(userId));
    }

    @Test
    void noMembership_isTheHonestEmpty_neverAWidenedScope() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertTrue(port.getActiveNeighborhoodId(userId).isEmpty());
    }
}
