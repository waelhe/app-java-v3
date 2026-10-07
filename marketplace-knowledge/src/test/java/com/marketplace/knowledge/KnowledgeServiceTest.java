package com.marketplace.knowledge;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B-14 (compliance plan C.4): the guide engine's contracts — the geo
 * gate order (the port's 404 before the level-3 400 before any write),
 * the author ownership (a foreign entry answers 404), the event
 * publication on every publish-relevant boundary (the upsert and drop
 * signals — the «search بالأحداث» integration), and the full
 * contribute-discover-revise-withdraw journey as one orchestration pin.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    @Mock
    private KnowledgeEntryRepository repository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Authentication authentication;

    private KnowledgeService service() {
        return new KnowledgeService(repository, geoLookupPort, currentUserProvider, eventPublisher);
    }

    private void callerIs(UUID userId) {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
    }

    private void neighborhoodNode() {
        when(geoLookupPort.getLocation(NEIGHBORHOOD_ID)).thenReturn(new GeoLookupPort.GeoNode(
                NEIGHBORHOOD_ID, UUID.randomUUID(), 3, "حي القضية", "Al-Qudayya", "al-qudayya", List.of()));
    }

    private KnowledgeEntryRequest request() {
        return new KnowledgeEntryRequest(NEIGHBORHOOD_ID, KnowledgeCategory.PLACES,
                "مسجد الحي: القصة والتاريخ", "بُني مسجد الحي في الطرف الشمالي قبل خمسين عامًا...");
    }

    @Test
    void contributePublishesTheCompleteIndexingFact() {
        callerIs(AUTHOR_ID);
        neighborhoodNode();
        when(repository.save(any(KnowledgeEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeEntry entry = service().contribute(request(), authentication);

        assertThat(entry.getAuthorId()).isEqualTo(AUTHOR_ID);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        KnowledgeEntryPublishedEvent event = (KnowledgeEntryPublishedEvent) published.getValue();
        assertThat(event.entryId()).isEqualTo(entry.getId());
        assertThat(event.locationId()).isEqualTo(NEIGHBORHOOD_ID);
        assertThat(event.category()).isEqualTo(KnowledgeCategory.PLACES);
        assertThat(event.title()).isEqualTo("مسجد الحي: القصة والتاريخ");
        assertThat(event.body()).startsWith("بُني مسجد الحي");
        assertThat(event.authorId()).isEqualTo(AUTHOR_ID);
    }

    @Test
    void contributeOfANonNeighborhoodNodeIs400BeforeAnyWrite() {
        callerIs(AUTHOR_ID);
        UUID cityId = create(UUID.class);
        when(geoLookupPort.getLocation(cityId)).thenReturn(new GeoLookupPort.GeoNode(
                cityId, null, 2, "الرياض", "Riyadh", "riyadh", List.of()));

        assertThatThrownBy(() -> service().contribute(
                new KnowledgeEntryRequest(cityId, KnowledgeCategory.HISTORY, "t", "b"),
                authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verifyNoInteractions(repository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void contributeOfAnUnknownNodeIsThePortsOwn404() {
        callerIs(AUTHOR_ID);
        UUID unknown = create(UUID.class);
        when(geoLookupPort.getLocation(unknown))
                .thenThrow(new ResourceNotFoundException("Location not found: " + unknown));

        assertThatThrownBy(() -> service().contribute(
                new KnowledgeEntryRequest(unknown, KnowledgeCategory.TIPS, "t", "b"),
                authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Location not found");
        verifyNoInteractions(repository);
    }

    @Test
    void searchComposesTheCategoryAxisIntoTheTextQuery() {
        String query = "مسجد الحي";
        when(repository.searchFullText(query, "PLACES", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service().search(query, KnowledgeCategory.PLACES, PageRequest.of(0, 20));

        verify(repository).searchFullText(query, "PLACES", PageRequest.of(0, 20));
    }

    @Test
    void searchWithoutTheCategoryAxisPassesNull() {
        when(repository.searchFullText("مسجد", null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service().search("مسجد", null, PageRequest.of(0, 20));

        verify(repository).searchFullText("مسجد", null, PageRequest.of(0, 20));
    }

    @Test
    void reviseRepublishesTheRevisedFact() {
        callerIs(AUTHOR_ID);
        KnowledgeEntry entry = KnowledgeEntry.contribute(AUTHOR_ID, NEIGHBORHOOD_ID,
                KnowledgeCategory.PLACES, "العنوان الأول", "المتن الأول");
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));

        service().revise(entry.getId(),
                new KnowledgeEntryRequest(NEIGHBORHOOD_ID, KnowledgeCategory.HISTORY,
                        "العنوان المراجع", "المتن المراجع"),
                authentication);

        assertThat(entry.getTitle()).isEqualTo("العنوان المراجع");
        assertThat(entry.getCategory()).isEqualTo(KnowledgeCategory.HISTORY);
        verify(eventPublisher).publishEvent(any(KnowledgeEntryPublishedEvent.class));
    }

    @Test
    void reviseOfAForeignEntryAnswers404AndPublishesNothing() {
        callerIs(AUTHOR_ID);
        UUID id = create(UUID.class);
        when(repository.findById(id)).thenReturn(Optional.of(
                KnowledgeEntry.contribute(UUID.randomUUID(), NEIGHBORHOOD_ID,
                        KnowledgeCategory.PEOPLE, "t", "b")));

        assertThatThrownBy(() -> service().revise(id,
                new KnowledgeEntryRequest(NEIGHBORHOOD_ID, KnowledgeCategory.PEOPLE, "t2", "b2"),
                authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Knowledge entry not found");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void withdrawSoftDeletesAndPublishesTheDropSignal() {
        callerIs(AUTHOR_ID);
        KnowledgeEntry entry = KnowledgeEntry.contribute(AUTHOR_ID, NEIGHBORHOOD_ID,
                KnowledgeCategory.SERVICES, "t", "b");
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));

        service().withdraw(entry.getId(), authentication);

        verify(repository).delete(entry);
        verify(eventPublisher).publishEvent(
                new KnowledgeEntryWithdrawnEvent(entry.getId(), NEIGHBORHOOD_ID));
    }

    @Test
    void withdrawOfAForeignEntryAnswers404AndPublishesNothing() {
        callerIs(AUTHOR_ID);
        UUID id = create(UUID.class);
        when(repository.findById(id)).thenReturn(Optional.of(
                KnowledgeEntry.contribute(UUID.randomUUID(), NEIGHBORHOOD_ID,
                        KnowledgeCategory.TIPS, "t", "b")));

        assertThatThrownBy(() -> service().withdraw(id, authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Knowledge entry not found");
        verify(repository, never()).delete(any());
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The module's DoD as a journey (the plan's §1.2 rule): contribute →
     * discover → revise → withdraw — one orchestration pin over the
     * mocked collaborators, with the event pair riding exactly the
     * boundaries the search integration consumes.
     */
    @Test
    void theFullJourneyContributeDiscoverReviseWithdraw() {
        KnowledgeService service = service();
        callerIs(AUTHOR_ID);
        neighborhoodNode();
        when(repository.save(any(KnowledgeEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. The member contributes — born published, the upsert signal rides the boundary.
        KnowledgeEntry entry = service.contribute(request(), authentication);
        verify(eventPublisher, times(1)).publishEvent(any(KnowledgeEntryPublishedEvent.class));

        // 2. The neighborhood board returns it.
        when(repository.findByLocationIdOrderByCreatedAtDescIdDesc(NEIGHBORHOOD_ID, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
        assertThat(service.board(NEIGHBORHOOD_ID, null, PageRequest.of(0, 20))).hasSize(1);

        // 3. The discovery surface finds it (the composed text+category query).
        when(repository.searchFullText("مسجد الحي", "PLACES", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
        assertThat(service.search("مسجد الحي", KnowledgeCategory.PLACES, PageRequest.of(0, 20))).hasSize(1);

        // 4. The author revises — the revised fact republishes (the index never serves stale text).
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));
        service.revise(entry.getId(),
                new KnowledgeEntryRequest(NEIGHBORHOOD_ID, KnowledgeCategory.HISTORY,
                        "مسجد الحي: الطبعة المراجعة", "المتن المراجع"),
                authentication);
        verify(eventPublisher, times(2)).publishEvent(any(KnowledgeEntryPublishedEvent.class));

        // 5. The author withdraws — the soft delete + the drop signal.
        service.withdraw(entry.getId(), authentication);
        verify(repository).delete(entry);
        verify(eventPublisher).publishEvent(new KnowledgeEntryWithdrawnEvent(entry.getId(), NEIGHBORHOOD_ID));
    }
}
