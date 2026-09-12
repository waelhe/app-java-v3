package com.marketplace.geo;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L30 service unit tests: the suggest floor, the slug-conflict 409s, the
 * delete-with-children 409, and the geo-tree invalidation event riding the
 * existing AFTER_COMMIT relay (the house's relay test seam).
 */
class GeoServiceTest {

    private GeoLocationRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private GeoService service;

    private GeoLocation root;
    private GeoLocation governorate;
    private GeoLocation city;

    @BeforeEach
    void setUp() {
        repository = mock(GeoLocationRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new GeoService(repository, eventPublisher);

        root = GeoLocation.createRoot("سوريا", "Syria", "syria");
        governorate = GeoLocation.createChild(root, "ريف دمشق", "Rif Dimashq", "rif-dimashq");
        city = GeoLocation.createChild(governorate, "قدسيا", "Qudsayya", "qudsayya");
    }

    @Test
    void suggest_belowTwoCharacters_is400BeforeAnyQuery() {
        assertThatThrownBy(() -> service.suggest("ق"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least 2");
        assertThatThrownBy(() -> service.suggest("  "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.suggest(null))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).suggestByPrefix(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void suggest_trimsThenQueries() {
        when(repository.suggestByPrefix("قد%", GeoService.SUGGEST_LIMIT))
                .thenReturn(List.of(city));

        List<GeoNode> results = service.suggest(" قد ");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).nameAr()).isEqualTo("قدسيا");
        assertThat(results.get(0).children()).isEmpty();
    }

    @Test
    void getTree_nestsTheHierarchyFromTheRoot() {
        when(repository.findAll()).thenReturn(List.of(root, governorate, city));

        GeoNode tree = service.getTree();

        assertThat(tree.nameAr()).isEqualTo("سوريا");
        assertThat(tree.children()).hasSize(1);
        GeoNode found = tree.children().get(0);
        assertThat(found.nameAr()).isEqualTo("ريف دمشق");
        assertThat(found.children()).hasSize(1);
        assertThat(found.children().get(0).nameAr()).isEqualTo("قدسيا");
        assertThat(found.children().get(0).level()).isEqualTo(2);
    }

    @Test
    void getChildren_unknownParent_is404() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getChildren(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findSelfAndDescendants_unknownLocation_is404() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSelfAndDescendants(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createChild_slugConflict_is409AndNothingPersisted() {
        UUID parentId = governorate.getId();
        when(repository.findById(parentId)).thenReturn(Optional.of(governorate));
        when(repository.existsBySlug("qudsayya")).thenReturn(true);

        assertThatThrownBy(() -> service.createChild(parentId, "قدسيا", null, "qudsayya"))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createChild_publishesGeoTreeInvalidation() {
        UUID parentId = governorate.getId();
        when(repository.findById(parentId)).thenReturn(Optional.of(governorate));
        when(repository.existsBySlug("qudsayya")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        GeoNode created = service.createChild(parentId, "قدسيا", null, "qudsayya");

        assertThat(created.level()).isEqualTo(2);
        ArgumentCaptor<CacheInvalidationRequested> captor =
                ArgumentCaptor.forClass(CacheInvalidationRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().cacheNames())
                .containsExactlyInAnyOrderElementsOf(GeoService.GEO_CACHE_NAMES);
    }

    @Test
    void delete_withChildren_is409NoDeletion() {
        UUID id = city.getId();
        when(repository.findById(id)).thenReturn(Optional.of(city));
        when(repository.existsByParentId(id)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has children");
        verify(repository, never()).delete(any(GeoLocation.class));
    }

    @Test
    void delete_childless_softDeletesAndInvalidates() {
        UUID id = city.getId();
        when(repository.findById(id)).thenReturn(Optional.of(city));
        when(repository.existsByParentId(id)).thenReturn(false);

        service.delete(id);

        verify(repository).delete(city);
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void update_reSlugToExistingSlug_is409() {
        UUID id = city.getId();
        when(repository.findById(id)).thenReturn(Optional.of(city));
        when(repository.existsBySlug("other")).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, "قدسيا", null, "other"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_keepsOwnSlugWithoutConflictCheck() {
        UUID id = city.getId();
        when(repository.findById(id)).thenReturn(Optional.of(city));

        GeoNode updated = service.update(id, "قدسيا الكبرى", "Qudsayya", "qudsayya");

        assertThat(updated.nameAr()).isEqualTo("قدسيا الكبرى");
        verify(repository, never()).existsBySlug("qudsayya");
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }
}
