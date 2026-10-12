package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationGeoSubscriptionView;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 (§8.1) — the opt-in geo-subscription service at the unit seam
 * (the #545 measured coverage gap: the routing engine's geo widening had
 * no service test). The contracts proven here:
 * <ul>
 *   <li>the list is SELF-scoped (the caller's own rows only);</li>
 *   <li>the write gate — the target must EXIST (404 unknown) and be
 *       LEVEL 3 (400 for a wider level);</li>
 *   <li>the idempotent subscribe — a live duplicate answers the existing
 *       row; a NEW pair persists through the REQUIRES_NEW writer;</li>
 *   <li>the CodeRabbit-adopted race contract — the loser of the
 *       {@code uq_notification_geo_subscriptions_live} create race
 *       absorbs the violation OUTSIDE the aborted transaction and answers
 *       the winner's committed row (the FollowService twin); every OTHER
 *       integrity violation propagates honestly;</li>
 *   <li>the withdrawal — the caller's own row, 404 for an unknown pair.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotificationGeoSubscriptionServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    @Mock
    private NotificationGeoSubscriptionRepository repository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private NotificationGeoSubscriptionWriter writer;

    private NotificationGeoSubscriptionService service() {
        return new NotificationGeoSubscriptionService(repository, geoLookupPort,
                currentUserProvider, writer);
    }

    private void callerIsTheUser() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER);
    }

    private void locationIsANeighborhood() {
        when(geoLookupPort.getLocation(LOCATION)).thenReturn(new GeoLookupPort.GeoNode(
                LOCATION, UUID.randomUUID(), 3, "حي القديسة", "Al-Qudayssa", "al-qudayssa"));
    }

    @Test
    void getMySubscriptions_isScopedToTheCaller() {
        callerIsTheUser();
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(USER)).thenReturn(List.of(
                NotificationGeoSubscription.subscribe(USER, LOCATION)));

        List<NotificationGeoSubscriptionView> mine = service().getMySubscriptions(null);

        assertThat(mine).singleElement().satisfies(view ->
                assertThat(view.locationId()).isEqualTo(LOCATION));
        verify(repository).findByUserIdOrderByCreatedAtDescIdDesc(USER);
    }

    @Test
    void subscribe_isIdempotentOnALiveDuplicate() {
        callerIsTheUser();
        locationIsANeighborhood();
        NotificationGeoSubscription existing =
                NotificationGeoSubscription.subscribe(USER, LOCATION);
        when(repository.findByUserIdAndLocationId(USER, LOCATION))
                .thenReturn(Optional.of(existing));

        NotificationGeoSubscriptionView view = service().subscribe(null, LOCATION);

        assertThat(view.locationId()).isEqualTo(LOCATION);
        verify(writer, never()).persist(any());
    }

    @Test
    void subscribe_persistsTheNewPairThroughTheRequiresNewWriter() {
        callerIsTheUser();
        locationIsANeighborhood();
        when(repository.findByUserIdAndLocationId(USER, LOCATION)).thenReturn(Optional.empty());
        NotificationGeoSubscription saved =
                NotificationGeoSubscription.subscribe(USER, LOCATION);
        when(writer.persist(any(NotificationGeoSubscription.class))).thenReturn(saved);

        NotificationGeoSubscriptionView view = service().subscribe(null, LOCATION);

        assertThat(view.locationId()).isEqualTo(LOCATION);
        verify(writer).persist(any(NotificationGeoSubscription.class));
    }

    @Test
    void subscribe_absorbsTheLostCreateRaceAndAnswersTheWinnersRow() {
        // CodeRabbit's round on the #545 slice (adopted 0a1d36e6): the
        // INSERT rode its own REQUIRES_NEW transaction, so the violation
        // crossed ITS proxy — the catch here sits OUTSIDE the aborted
        // transaction and the fresh-transaction replay answers the
        // winner's committed row: the documented idempotent 200.
        callerIsTheUser();
        locationIsANeighborhood();
        when(repository.findByUserIdAndLocationId(USER, LOCATION)).thenReturn(Optional.empty());
        when(writer.persist(any(NotificationGeoSubscription.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_notification_geo_subscriptions_live\""));
        NotificationGeoSubscription winnersRow =
                NotificationGeoSubscription.subscribe(USER, LOCATION);
        when(writer.replay(USER, LOCATION)).thenReturn(winnersRow);

        NotificationGeoSubscriptionView view = service().subscribe(null, LOCATION);

        assertThat(view.locationId()).isEqualTo(LOCATION);
        verify(writer).replay(USER, LOCATION);
    }

    @Test
    void subscribe_propagatesAForeignIntegrityViolation() {
        // Only the one-live-subscription constraint maps to the
        // idempotent answer — every other violation is an honest failure.
        callerIsTheUser();
        locationIsANeighborhood();
        when(repository.findByUserIdAndLocationId(USER, LOCATION)).thenReturn(Optional.empty());
        when(writer.persist(any(NotificationGeoSubscription.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_follows_one_live\""));

        assertThatThrownBy(() -> service().subscribe(null, LOCATION))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(writer, never()).replay(any(), any());
    }

    @Test
    void subscribe_rejectsANonNeighborhoodLevel() {
        when(geoLookupPort.getLocation(LOCATION)).thenReturn(new GeoLookupPort.GeoNode(
                LOCATION, null, 2, "المنطقة الوسطى", "Central Region", "central-region"));

        assertThatThrownBy(() -> service().subscribe(null, LOCATION))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verify(writer, never()).persist(any());
    }

    @Test
    void subscribe_rejectsAnUnknownLocationWithTheHonestNotFound() {
        when(geoLookupPort.getLocation(LOCATION))
                .thenThrow(new ResourceNotFoundException("Unknown location " + LOCATION));

        assertThatThrownBy(() -> service().subscribe(null, LOCATION))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(writer, never()).persist(any());
    }

    @Test
    void withdraw_deletesTheCallersOwnRow() {
        callerIsTheUser();
        NotificationGeoSubscription row =
                NotificationGeoSubscription.subscribe(USER, LOCATION);
        when(repository.findByUserIdAndLocationId(USER, LOCATION))
                .thenReturn(Optional.of(row));

        service().withdraw(null, LOCATION);

        verify(repository).delete(row);
    }

    @Test
    void withdraw_answersNotFoundForAnUnknownPair() {
        callerIsTheUser();
        when(repository.findByUserIdAndLocationId(USER, LOCATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().withdraw(null, LOCATION))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }
}
