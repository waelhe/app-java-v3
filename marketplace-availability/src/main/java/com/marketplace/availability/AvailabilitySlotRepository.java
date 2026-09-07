package com.marketplace.availability;

import com.marketplace.shared.api.SlotWindowStats;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, UUID>, RevisionRepository<AvailabilitySlot, UUID, Integer> {
    List<AvailabilitySlot> findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(UUID providerId, Instant from, Instant to);
    boolean existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(UUID providerId, Instant endsAt, Instant startsAt);

    Optional<AvailabilitySlot> findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(UUID providerId, Instant startsAt, Instant endsAt);

    Optional<AvailabilitySlot> findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(UUID providerId, Instant startsAt, Instant endsAt);

    /**
     * L27 (feature-expansion roadmap §5): the bulk form of the
     * {@code existsBy...StartsAtLessThanAndEndsAtGreaterThan} predicate that
     * backs {@code AvailabilityService.isAvailable} — same strict overlap
     * inequalities per provider, minus the providers carrying a conflicting
     * time-off. One provider qualifies when:
     *
     * <ul>
     *   <li>it owns a slot with {@code booked = false} overlapping the window — i.e.
     *       {@code slot.starts_at < :endsAt AND slot.ends_at > :startsAt}: an
     *       open-interval overlap, so a slot ending exactly at the window
     *       start neither qualifies nor conflicts (the {@code [from, to)}
     *       exclusive-end convention), and</li>
     *   <li>no time-off row of the same provider satisfies
     *       {@code t.starts_at < :endsAt AND t.ends_at > :startsAt}.</li>
     * </ul>
     *
     * <p>Consumed through {@code AvailabilityLookupAdapter} (the shared-api
     * {@code AvailabilityLookupPort}) by the search module's window filter.
     */
    @Query("""
            SELECT DISTINCT s.providerId FROM AvailabilitySlot s
            WHERE s.booked = false
              AND s.startsAt < :endsAt
              AND s.endsAt > :startsAt
              AND s.providerId NOT IN (
                  SELECT t.providerId FROM ProviderTimeOff t
                  WHERE t.startsAt < :endsAt AND t.endsAt > :startsAt)
            """)
    Set<UUID> findAvailableProviderIds(@Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);

    /**
     * L25 (feature-expansion roadmap §5): one provider's slot-window
     * aggregates — the constructor projection backs
     * {@code AvailabilityLookupPort.findProviderSlotStats}. A slot belongs
     * to the window when its {@code startsAt} lies in {@code [from, to)}
     * (the house exclusive-end convention).
     */
    @Query("""
            SELECT new com.marketplace.shared.api.SlotWindowStats(
                COUNT(s),
                SUM(CASE WHEN s.booked = true THEN 1 ELSE 0 END))
            FROM AvailabilitySlot s
            WHERE s.providerId = :providerId
              AND s.startsAt >= :from
              AND s.startsAt < :to
            """)
    SlotWindowStats findProviderSlotStats(@Param("providerId") UUID providerId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);
}
