package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.NewListingInNeighborhoodEvent;
import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L41 — the membership service's gate order and the switch's write order,
 * unit-pinned (the integration test proves both against the real schema):
 *
 * <ul>
 *   <li>the geo existence gate answers the port's own 404;</li>
 *   <li>the level-3 gate answers 400 BEFORE any write;</li>
 *   <li>the switch's write order is delete → flush → save (the measured
 *       Hibernate flush-ordering fact — inserts flush before updates, so
 *       the soft-delete must land before the new insert queues its
 *       unique-index check);</li>
 *   <li>the leave/read 404s follow the /me convention.</li>
 * </ul>
 *
 * <p>L46 — the realestate bridge unit half (the integration test proves
 * the full chain over the real event registry): the documented skip for
 * non-real-estate listings, the per-active-member fan-out, the
 * publisher-member exclusion, the no-member zero, and the failure
 * propagation that keeps the registry entry incomplete for retry.
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodMembershipServiceTest {

    private static final Instant FIXED = Instant.parse("2026-09-17T09:30:00Z");

    @Mock
    private NeighborhoodMembershipRepository repository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private PropertyDetailsPort propertyDetailsPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private NeighborhoodMembershipService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new NeighborhoodMembershipService(repository, geoLookupPort,
                propertyDetailsPort, eventPublisher, clock);
    }

    private UUID userId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();

    private GeoLookupPort.GeoNode node(int level) {
        return new GeoLookupPort.GeoNode(locationId, null, level, "حي", null, "node");
    }

    private NeighborhoodMembership storedMembership(UUID location) {
        return NeighborhoodMembership.join(userId, location, clock);
    }

    @Test
    void join_unknownLocation_isThePortsOwn404() {
        when(geoLookupPort.getLocation(locationId))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        assertThatThrownBy(() -> service.join(userId, locationId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void join_nonLevel3Node_is400BeforeAnyWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(0));

        assertThatThrownBy(() -> service.join(userId, locationId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verify(repository, never()).findByUserId(userId);

        when(geoLookupPort.getLocation(locationId)).thenReturn(node(2));
        assertThatThrownBy(() -> service.join(userId, locationId))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void join_fresh_savesAndReportsCreated() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        NeighborhoodMembership stored = storedMembership(locationId);
        when(repository.save(any())).thenReturn(stored);

        var result = service.join(userId, locationId);

        assertThat(result.created()).isTrue();
        assertThat(result.view().locationId()).isEqualTo(locationId);
        assertThat(result.view().verificationState()).isEqualTo("SELF_DECLARED");
        verify(repository, never()).delete(any());
    }

    @Test
    void join_sameLocation_isIdempotentNoWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(repository.findByUserId(userId))
                .thenReturn(Optional.of(storedMembership(locationId)));

        var result = service.join(userId, locationId);

        assertThat(result.created()).isFalse();
        assertThat(result.view().locationId()).isEqualTo(locationId);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void join_differentLocation_switchesInDeleteFlushSaveOrder() {
        UUID oldLocation = UUID.randomUUID();
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        NeighborhoodMembership old = storedMembership(oldLocation);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(old));
        NeighborhoodMembership fresh = storedMembership(locationId);
        when(repository.save(any())).thenReturn(fresh);

        var result = service.join(userId, locationId);

        assertThat(result.created()).isTrue();
        // The measured ordering: the soft-delete (UPDATE) must flush BEFORE
        // the new INSERT queues its partial-unique-index check.
        InOrder order = inOrder(repository);
        order.verify(repository).delete(old);
        order.verify(repository).flush();
        order.verify(repository).save(any());
    }

    @Test
    void getMine_noMembership_is404() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMine(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMine_returnsTheStoredView() {
        NeighborhoodMembership stored = storedMembership(locationId);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(stored));

        var view = service.getMine(userId);

        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.locationId()).isEqualTo(locationId);
        assertThat(view.memberSince()).isEqualTo(FIXED);
    }

    @Test
    void leave_noMembership_is404() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(userId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void leave_softDeletesTheActiveMembership() {
        NeighborhoodMembership stored = storedMembership(locationId);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(stored));

        service.leave(userId);

        verify(repository).delete(stored);
    }

    @Test
    void switch_writeFailure_propagatesAfterDelete() {
        // The unit-level half of the atomicity criterion: the delete and
        // the insert are one service method — a save failure propagates
        // OUT of the command, and the transactional rollback (proven on
        // the real schema by the integration test's spy) is what restores
        // the old row.
        UUID oldLocation = UUID.randomUUID();
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        NeighborhoodMembership old = storedMembership(oldLocation);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(old));
        doThrow(new IllegalStateException("simulated create failure"))
                .when(repository).save(any());

        assertThatThrownBy(() -> service.join(userId, locationId))
                .isInstanceOf(IllegalStateException.class);
        verify(repository).delete(old);
        verify(repository).flush();
    }

    // ------------------------------------------------------------------
    // L46 — the community realestate bridge (unit half)
    // ------------------------------------------------------------------

    private UUID listingId = UUID.randomUUID();
    private UUID providerId = UUID.randomUUID();

    private PropertyDetailsPort.PropertyView propertyAt(UUID location) {
        return new PropertyDetailsPort.PropertyView(listingId, PropertyPurpose.RENT,
                PropertyType.APARTMENT, 90, 3, 1, 2, 5, 2010, false,
                List.of(), null, location, null, null);
    }

    private NeighborhoodMembership memberOf(UUID memberId, UUID location) {
        return NeighborhoodMembership.join(memberId, location, clock);
    }

    @Test
    void onListingActivated_propertyPresent_publishesOneEventPerActiveMember() {
        // Criterion 1 (unit half): the property's locationId resolves the
        // neighborhood, and EVERY active member of that node gets exactly
        // one event carrying (memberId, listingId).
        UUID neighborA = UUID.randomUUID();
        UUID neighborB = UUID.randomUUID();
        when(propertyDetailsPort.findByListingId(listingId))
                .thenReturn(Optional.of(propertyAt(locationId)));
        when(repository.findByLocationId(locationId)).thenReturn(List.of(
                memberOf(neighborA, locationId), memberOf(neighborB, locationId)));

        int alerted = service.onListingActivated(listingId, providerId);

        assertThat(alerted).isEqualTo(2);
        org.mockito.ArgumentCaptor<NewListingInNeighborhoodEvent> captor =
                org.mockito.ArgumentCaptor.forClass(NewListingInNeighborhoodEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NewListingInNeighborhoodEvent::recipientId)
                .containsExactlyInAnyOrder(neighborA, neighborB);
        assertThat(captor.getAllValues())
                .extracting(NewListingInNeighborhoodEvent::listingId)
                .containsOnly(listingId);
    }

    @Test
    void onListingActivated_noPropertyDetails_isTheDocumentedSkip() {
        // Criterion 2 (the negative): a listing with no property_details
        // row is not real-estate — no neighborhood to bridge, zero events,
        // and the membership query never even runs.
        when(propertyDetailsPort.findByListingId(listingId)).thenReturn(Optional.empty());

        int alerted = service.onListingActivated(listingId, providerId);

        assertThat(alerted).isZero();
        verify(eventPublisher, never()).publishEvent(any());
        verify(repository, never()).findByLocationId(any());
    }

    @Test
    void onListingActivated_publisherMemberIsTheOneMemberNotAlerted() {
        // Criterion 4 (the negative): the listing's publisher IS a member
        // of the listing's own neighborhood — the conflict-of-interest
        // exclusion — while the OTHER member is still alerted.
        UUID neighbor = UUID.randomUUID();
        when(propertyDetailsPort.findByListingId(listingId))
                .thenReturn(Optional.of(propertyAt(locationId)));
        when(repository.findByLocationId(locationId)).thenReturn(List.of(
                memberOf(providerId, locationId), memberOf(neighbor, locationId)));

        int alerted = service.onListingActivated(listingId, providerId);

        assertThat(alerted).isEqualTo(1);
        org.mockito.ArgumentCaptor<NewListingInNeighborhoodEvent> captor =
                org.mockito.ArgumentCaptor.forClass(NewListingInNeighborhoodEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(1))
                .publishEvent(captor.capture());
        assertThat(captor.getValue().recipientId()).isEqualTo(neighbor);
    }

    @Test
    void onListingActivated_neighborhoodWithoutMembers_isZeroNotificationsZeroErrors() {
        // Criterion 5: a listing in a node nobody joined — the query itself
        // is the scope, so zero events and no exception of any kind.
        when(propertyDetailsPort.findByListingId(listingId))
                .thenReturn(Optional.of(propertyAt(locationId)));
        when(repository.findByLocationId(locationId)).thenReturn(List.of());

        int alerted = service.onListingActivated(listingId, providerId);

        assertThat(alerted).isZero();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onListingActivated_failurePropagatesForTheRegistrysRetry() {
        // Criterion 3 (the unit-level failure injection — the root every
        // house listener pins, the NotificationEventListenerTest
        // propagatesException shape): a mid-bridge failure must propagate
        // OUT of the listener's transaction, because the publication
        // registry only keeps the ListingActivatedEvent entry incomplete
        // (and retries it) when the listener run FAILS — a swallowed
        // exception would mark the activation complete and silently lose
        // the match for every member.
        when(propertyDetailsPort.findByListingId(listingId))
                .thenReturn(Optional.of(propertyAt(locationId)));
        when(repository.findByLocationId(locationId))
                .thenThrow(new IllegalStateException("simulated member-query failure"));

        assertThatThrownBy(() -> service.onListingActivated(listingId, providerId))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
