package com.marketplace.institutions;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UrgentAlertPublishedEvent;
import com.marketplace.shared.api.UrgentAlertWithdrawnEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the official-alert
 * engine's contracts — the delegation machine's one-way discipline (the
 * registry quartet verbatim), the publish gate order (VERIFIED source →
 * level-3 geo → window rule, all BEFORE any write), the withdrawal's
 * honesty leg with its own Modulith signal, and the eligibility engine's
 * AC-20-01 leg (a VERIFIED source is a display precondition — an
 * unverified source's alerts never leak, deterministically).
 */
@ExtendWith(MockitoExtension.class)
class UrgentAlertServiceTest {

    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    @Mock
    private UrgentAlertSourceRepository sourceRepository;

    @Mock
    private UrgentAlertRepository alertRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    private UrgentAlertService service() {
        return new UrgentAlertService(sourceRepository, alertRepository, geoLookupPort, eventPublisher, clock);
    }

    private void neighborhoodNode() {
        when(geoLookupPort.getLocation(NEIGHBORHOOD_ID)).thenReturn(new GeoLookupPort.GeoNode(
                NEIGHBORHOOD_ID, UUID.randomUUID(), 3, "حي القضية", "Al-Qudayya", "al-qudayya", List.of()));
    }

    private UrgentAlertPublishRequest publishRequest(UrgentAlertSource source) {
        return new UrgentAlertPublishRequest(source.getId(), NEIGHBORHOOD_ID,
                UrgentAlertLevel.CRITICAL, "انقطاع المياه صباح الخميس",
                "توقف ضخ المياه في الحي من 8 صباحاً حتى 2 ظهراً لأعمال صيانة مجدولة.",
                now.minusSeconds(60), now.plusSeconds(3600));
    }

    @Test
    void createSourceIsBornUnverifiedOnTheHonestRegistry() {
        when(sourceRepository.save(any(UrgentAlertSource.class))).thenAnswer(inv -> inv.getArgument(0));

        UrgentAlertSource source = service().createSource(
                new UrgentAlertSourceRequest("أمانة محافظة الرياض", UrgentAlertSourceType.MUNICIPALITY));

        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.UNVERIFIED);
        assertThat(source.getSourceType()).isEqualTo(UrgentAlertSourceType.MUNICIPALITY);
        assertThat(source.getName()).isEqualTo("أمانة محافظة الرياض");
    }

    @Test
    void requestVerificationMovesOnlyTheUnverifiedSourceToPending() {
        UrgentAlertSource source = UrgentAlertSource.delegate("الدفاع المدني", UrgentAlertSourceType.CIVIL_DEFENSE);
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        service().requestSourceVerification(source.getId());

        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.PENDING);
    }

    @Test
    void requestVerificationOfAnUnknownSourceAnswers404() {
        UUID unknown = create(UUID.class);
        when(sourceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requestSourceVerification(unknown))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Urgent alert source not found");
    }

    @Test
    void reviewConfirmsAPendingSourceToVerified() {
        UrgentAlertSource source = UrgentAlertSource.delegate("أمانة محافظة الرياض", UrgentAlertSourceType.MUNICIPALITY);
        source.requestVerification(); // PENDING — the reviewable claim
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        service().reviewSource(source.getId(), true);

        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);
    }

    @Test
    void reviewRejectsAPendingSource() {
        UrgentAlertSource source = UrgentAlertSource.delegate("سلطة صحة", UrgentAlertSourceType.HEALTH_AUTHORITY);
        source.requestVerification();
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        service().reviewSource(source.getId(), false);

        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.REJECTED);
    }

    @Test
    void reviewReAdmitsARejectedSource() {
        UrgentAlertSource source = UrgentAlertSource.delegate("سلطة تعليم", UrgentAlertSourceType.EDUCATION_AUTHORITY);
        source.requestVerification();
        source.rejectVerification();
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        service().reviewSource(source.getId(), true);

        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);
    }

    @Test
    void reviewOfAnIllegalSourceAnswers409WithTheMachinesOwnWords() {
        UrgentAlertSource source = UrgentAlertSource.delegate("بلدية", UrgentAlertSourceType.MUNICIPALITY);
        // Still UNVERIFIED — neither the confirm nor the reject lever accepts it.
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service().reviewSource(source.getId(), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only PENDING");
    }

    @Test
    void publishRequiresAVerifiedSource() {
        UrgentAlertSource source = UrgentAlertSource.delegate("بلدية", UrgentAlertSourceType.MUNICIPALITY);
        source.requestVerification(); // PENDING — not yet delegated
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service().publishAlert(publishRequest(source)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only VERIFIED sources can publish urgent alerts");
        verifyNoInteractions(alertRepository, eventPublisher);
    }

    @Test
    void publishOfAnUnknownSourceAnswers404() {
        UUID unknown = create(UUID.class);
        when(sourceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().publishAlert(new UrgentAlertPublishRequest(
                unknown, NEIGHBORHOOD_ID, UrgentAlertLevel.ADVISORY, "t", "b",
                now.minusSeconds(60), null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Urgent alert source not found");
        verifyNoInteractions(geoLookupPort, alertRepository, eventPublisher);
    }

    @Test
    void publishOfANonNeighborhoodNodeIs400BeforeAnyWrite() {
        UrgentAlertSource source = verifiedSource();
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        UUID cityId = create(UUID.class);
        when(geoLookupPort.getLocation(cityId)).thenReturn(new GeoLookupPort.GeoNode(
                cityId, null, 2, "الرياض", "Riyadh", "riyadh", List.of()));

        assertThatThrownBy(() -> service().publishAlert(new UrgentAlertPublishRequest(
                source.getId(), cityId, UrgentAlertLevel.SEVERE, "t", "b",
                now.minusSeconds(60), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verifyNoInteractions(alertRepository, eventPublisher);
    }

    @Test
    void publishOfAnUnknownNodeIsThePortsOwn404() {
        UrgentAlertSource source = verifiedSource();
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        UUID unknown = create(UUID.class);
        when(geoLookupPort.getLocation(unknown))
                .thenThrow(new ResourceNotFoundException("Location not found: " + unknown));

        assertThatThrownBy(() -> service().publishAlert(new UrgentAlertPublishRequest(
                source.getId(), unknown, UrgentAlertLevel.SEVERE, "t", "b",
                now.minusSeconds(60), null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Location not found");
        verifyNoInteractions(alertRepository, eventPublisher);
    }

    @Test
    void publishRejectsAnInvertedWindow() {
        UrgentAlertSource source = verifiedSource();
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        neighborhoodNode();

        // validUntil == validFrom — the window must be strictly open at its end.
        assertThatThrownBy(() -> service().publishAlert(new UrgentAlertPublishRequest(
                source.getId(), NEIGHBORHOOD_ID, UrgentAlertLevel.ADVISORY, "t", "b",
                now, now)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("strictly after validFrom");
        verifyNoInteractions(alertRepository, eventPublisher);
    }

    @Test
    void publishSavesTheAlertAndPublishesTheModulithEvent() {
        UrgentAlertSource source = verifiedSource();
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        neighborhoodNode();
        when(alertRepository.save(any(UrgentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        UrgentAlertService.ActiveAlert active = service().publishAlert(publishRequest(source));

        assertThat(active.alert().getSourceId()).isEqualTo(source.getId());
        assertThat(active.alert().getLocationId()).isEqualTo(NEIGHBORHOOD_ID);
        assertThat(active.alert().isWithdrawn()).isFalse();
        assertThat(active.source()).isSameAs(source);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(UrgentAlertPublishedEvent.class);
        UrgentAlertPublishedEvent event = (UrgentAlertPublishedEvent) events.getValue();
        assertThat(event.alertId()).isEqualTo(active.alert().getId());
        assertThat(event.sourceId()).isEqualTo(source.getId());
        assertThat(event.sourceName()).isEqualTo("أمانة محافظة الرياض");
        assertThat(event.locationId()).isEqualTo(NEIGHBORHOOD_ID);
        // CMP-46: the level rides as TEXT — the same string every surface renders.
        assertThat(event.level()).isEqualTo("CRITICAL");
        assertThat(event.title()).isEqualTo("انقطاع المياه صباح الخميس");
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    void withdrawSetsTheHonestyFlagsAndPublishesTheWithdrawnEvent() {
        UrgentAlertSource source = verifiedSource();
        UrgentAlert alert = liveAlert(source);
        when(alertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));

        UrgentAlertService.ActiveAlert active = service().withdrawAlert(alert.getId());

        assertThat(active.alert().isWithdrawn()).isTrue();
        assertThat(active.alert().getWithdrawnAt()).isEqualTo(now);
        // The row-lifecycle leg stays untouched — the withdrawal is NOT a soft delete.
        assertThat(active.alert().getId()).isEqualTo(alert.getId());

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(UrgentAlertWithdrawnEvent.class);
        UrgentAlertWithdrawnEvent event = (UrgentAlertWithdrawnEvent) events.getValue();
        assertThat(event.alertId()).isEqualTo(alert.getId());
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    void withdrawOfAnAlreadyWithdrawnAlertAnswers409() {
        UrgentAlertSource source = verifiedSource();
        UrgentAlert alert = liveAlert(source);
        alert.withdraw(now.minusSeconds(30));
        when(alertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service().withdrawAlert(alert.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only a live alert can be withdrawn");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void withdrawOfAnUnknownAlertAnswers404() {
        UUID unknown = create(UUID.class);
        when(alertRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().withdrawAlert(unknown))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Urgent alert not found");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void activeAlertsDropsTheAlertsOfUnverifiedSources() {
        UrgentAlertSource verified = verifiedSource();
        UrgentAlertSource pending = UrgentAlertSource.delegate("بلدية قيد التوثيق", UrgentAlertSourceType.MUNICIPALITY);
        pending.requestVerification(); // PENDING — AC-20-01: never eligible
        UrgentAlert first = liveAlert(verified);   // the repo's freshest-first order
        UrgentAlert second = liveAlert(pending);
        when(alertRepository.findLiveByLocation(NEIGHBORHOOD_ID, now))
                .thenReturn(List.of(first, second));
        when(sourceRepository.findAllById(any())).thenReturn(List.of(verified, pending));

        List<UrgentAlertService.ActiveAlert> active = service().activeAlerts(NEIGHBORHOOD_ID, now);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).alert().getId()).isEqualTo(first.getId());
        assertThat(active.get(0).source().getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);
    }

    @Test
    void activeAlertsOfAnEmptyLocationAnswersEmptyWithoutTouchingSources() {
        when(alertRepository.findLiveByLocation(NEIGHBORHOOD_ID, now)).thenReturn(List.of());

        assertThat(service().activeAlerts(NEIGHBORHOOD_ID, now)).isEmpty();
        verifyNoInteractions(sourceRepository);
    }

    @Test
    void reviewQueueDrainsOnTheStatesOwnClock() {
        Sort queueSort = Sort.by(Sort.Direction.ASC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        when(sourceRepository.findByVerificationState(InstitutionVerificationState.PENDING,
                PageRequest.of(0, 20, queueSort)))
                .thenReturn(new PageImpl<>(List.of(pendingSource()),
                        PageRequest.of(0, 20, queueSort), 1));

        var page = service().reviewQueue(InstitutionVerificationState.PENDING, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getVerificationState()).isEqualTo(InstitutionVerificationState.PENDING);
    }

    /**
     * The module's DoD as a journey (the plan's §1.2 rule): delegate →
     * confirm → publish (the Modulith signal) → withdraw (the honesty
     * signal) — one orchestration pin over the whole CMP-46/JT-10
     * lifecycle.
     */
    @Test
    void theFullJourneyDelegateConfirmPublishWithdraw() {
        UrgentAlertService service = service();
        when(sourceRepository.save(any(UrgentAlertSource.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. The admin registers the delegating body — born UNVERIFIED.
        UrgentAlertSource source = service.createSource(
                new UrgentAlertSourceRequest("شركة المياه الوطنية", UrgentAlertSourceType.UTILITIES));
        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.UNVERIFIED);

        // 2. The review request, then the verdict — the delegation lands.
        when(sourceRepository.findById(source.getId())).thenReturn(Optional.of(source));
        service.requestSourceVerification(source.getId());
        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.PENDING);
        service.reviewSource(source.getId(), true);
        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);

        // 3. The publish — the geo gate passes, the event rides the transaction.
        neighborhoodNode();
        when(alertRepository.save(any(UrgentAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        UrgentAlertService.ActiveAlert published = service.publishAlert(publishRequest(source));
        verify(eventPublisher).publishEvent(any(UrgentAlertPublishedEvent.class));

        // 4. The withdrawal — the honesty leg answers silence on every surface.
        when(alertRepository.findById(published.alert().getId()))
                .thenReturn(Optional.of(published.alert()));
        UrgentAlertService.ActiveAlert withdrawn = service.withdrawAlert(published.alert().getId());
        assertThat(withdrawn.alert().isWithdrawn()).isTrue();
        verify(eventPublisher).publishEvent(any(UrgentAlertWithdrawnEvent.class));
    }

    private UrgentAlertSource verifiedSource() {
        UrgentAlertSource source = UrgentAlertSource.delegate("أمانة محافظة الرياض", UrgentAlertSourceType.MUNICIPALITY);
        source.requestVerification();
        source.approveVerification();
        return source;
    }

    private UrgentAlertSource pendingSource() {
        UrgentAlertSource source = UrgentAlertSource.delegate("بلدية", UrgentAlertSourceType.MUNICIPALITY);
        source.requestVerification();
        return source;
    }

    private UrgentAlert liveAlert(UrgentAlertSource source) {
        return UrgentAlert.publish(source.getId(), NEIGHBORHOOD_ID, UrgentAlertLevel.CRITICAL,
                "انقطاع المياه صباح الخميس", "توقف ضخ المياه في الحي لأعمال صيانة مجدولة.",
                now.minusSeconds(60), now.plusSeconds(3600));
    }
}
