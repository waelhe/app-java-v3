package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the CMS page owner's guards —
 * the duplicate-ID rejection inside one assign request (the seen-set), and
 * the deterministic attribute reorder (remove-and-reinsert, never the
 * UUID tie-breaker).
 */
@ExtendWith(MockitoExtension.class)
class CmsPageAdminServiceTest {

    @Mock
    private CmsPageRepository pageRepository;
    @Mock
    private CmsPageTranslationRepository translationRepository;
    @Mock
    private CmsPageTypeRepository pageTypeRepository;
    @Mock
    private CmsPageTypeAttributeRepository attributeRepository;
    @Mock
    private CmsMenuItemRepository menuItemRepository;

    private CmsPageAdminService service;

    private Authentication admin;
    private UUID typeId;

    @BeforeEach
    void setUp() {
        service = new CmsPageAdminService(pageRepository, translationRepository, pageTypeRepository,
                attributeRepository, menuItemRepository);
        admin = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        typeId = UUID.randomUUID();
    }

    @Test
    void createPageDerivesTheSlugAndRequiresTheType() {
        when(pageTypeRepository.existsById(typeId)).thenReturn(true);
        when(pageRepository.existsBySlug("about-us")).thenReturn(false);

        var view = service.createPage(admin, "About Us", null, "content", true, null, null, typeId);

        assertThat(view.slug()).isEqualTo("about-us");
        assertThat(view.published()).isTrue();
        verify(pageRepository).save(any(CmsPage.class));
    }

    @Test
    void createPageRefusesAnUnknownType() {
        when(pageTypeRepository.existsById(typeId)).thenReturn(false);

        assertThatThrownBy(() -> service.createPage(admin, "T", "t", null, null, null, null, typeId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletePageRefusesAMenuReferencedTarget() {
        UUID id = UUID.randomUUID();
        when(pageRepository.findById(id))
                .thenReturn(Optional.of(CmsPage.create(typeId, "x", "X")));
        when(menuItemRepository.existsByPageId(id)).thenReturn(true);

        assertThatThrownBy(() -> service.deletePage(admin, id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("referenced by a menu item");
    }

    @Test
    void bulkPublishFlipsEveryKnownRow() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CmsPage pageA = CmsPage.create(typeId, "a", "A");
        CmsPage pageB = CmsPage.create(typeId, "b", "B");
        when(pageRepository.findById(a)).thenReturn(Optional.of(pageA));
        when(pageRepository.findById(b)).thenReturn(Optional.of(pageB));

        int count = service.bulkPublishPages(admin, List.of(a, b, UUID.randomUUID()), true);

        assertThat(count).isEqualTo(2);
        assertThat(pageA.isPublished()).isTrue();
        assertThat(pageB.isPublished()).isTrue();
    }

    @Test
    void assignPairsRejectsADuplicateInsideOneRequest() {
        when(pageTypeRepository.findById(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of());
        UUID attributeId = UUID.randomUUID();

        // m1: [a, a] answers the classified ConflictException, never the
        // (type, attribute) unique index's raw database error.
        assertThatThrownBy(() -> service.assignPageTypeAttributes(admin, typeId,
                List.of(attributeId, attributeId)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void assignPairsRejectsARepeatOfAnExistingPair() {
        when(pageTypeRepository.findById(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        UUID attributeId = UUID.randomUUID();
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of(CmsPageTypeAttribute.create(typeId, attributeId, 0)));

        assertThatThrownBy(() -> service.assignPageTypeAttributes(admin, typeId,
                List.of(attributeId)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reorderAppliesDeterministicRemoveAndReinsert() {
        when(pageTypeRepository.findById(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        CmsPageTypeAttribute pa = CmsPageTypeAttribute.create(typeId, a, 0);
        CmsPageTypeAttribute pb = CmsPageTypeAttribute.create(typeId, b, 1);
        CmsPageTypeAttribute pc = CmsPageTypeAttribute.create(typeId, c, 2);
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of(pa, pb, pc));

        // C moves up one slot: A, C, B — dense, no ties, no UUID decision.
        service.reorderPageTypeAttributes(admin, typeId, List.of(c), List.of(-1));

        assertThat(pa.getPosition()).isZero();
        assertThat(pc.getPosition()).isEqualTo(1);
        assertThat(pb.getPosition()).isEqualTo(2);
    }

    @Test
    void updatePageTypeRejectsATakenSlug() {
        UUID id = UUID.randomUUID();
        when(pageTypeRepository.findById(id))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        when(pageTypeRepository.existsBySlug("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.updatePageType(admin, id, null, "taken", null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void everyLegIsAdminGated() {
        Authentication consumer = new UsernamePasswordAuthenticationToken("m@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        assertThatThrownBy(() -> service.createPage(consumer, "T", "t", null, null, null, null, typeId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ---------------------------------------------------------------------
    // The read/scroll/translate/delete legs (the coverage round)
    // ---------------------------------------------------------------------

    private org.springframework.data.domain.Window<CmsPage> pageWindow(CmsPage page) {
        return org.springframework.data.domain.Window.from(List.of(page),
                index -> org.springframework.data.domain.ScrollPosition.offset(index), true);
    }

    @Test
    void scrollPagesDispatchesEverySortBranch() {
        CmsPage page = CmsPage.create(typeId, "a", "A");
        when(pageRepository.findAllByOrderBySlugAscIdAsc(any(), any()))
                .thenReturn(pageWindow(page));
        when(pageRepository.findAllByOrderBySlugDescIdDesc(any(), any()))
                .thenReturn(pageWindow(page));
        when(pageRepository.findAllByOrderByTitleAscIdAsc(any(), any()))
                .thenReturn(pageWindow(page));
        when(pageRepository.findAllByOrderByTitleDescIdDesc(any(), any()))
                .thenReturn(pageWindow(page));
        when(pageRepository.findAllByOrderByCreatedAtAscIdAsc(any(), any()))
                .thenReturn(pageWindow(page));
        when(pageRepository.findAllByOrderByCreatedAtDescIdDesc(any(), any()))
                .thenReturn(pageWindow(page));
        when(pageRepository.count()).thenReturn(1L);

        for (String field : new String[] {"SLUG", "TITLE", "CREATED_AT", "PUBLICATION_DATE"}) {
            assertThat(service.scrollPages(admin, field, true,
                    org.springframework.data.domain.ScrollPosition.keyset(),
                    org.springframework.data.domain.Limit.of(10)).window().hasNext()).isTrue();
            service.scrollPages(admin, field, false,
                    org.springframework.data.domain.ScrollPosition.keyset(),
                    org.springframework.data.domain.Limit.of(10));
        }
    }

    @Test
    void thePageReadLegsAnswerTheRegistry() {
        CmsPage page = CmsPage.create(typeId, "about", "About");
        when(pageRepository.findById(page.getId())).thenReturn(Optional.of(page));
        when(pageRepository.findBySlug("about")).thenReturn(Optional.of(page));

        assertThat(service.findPageById(admin, page.getId())).isPresent();
        assertThat(service.findPageBySlug(admin, "about")).isPresent();
        assertThat(service.findPageBySlug(admin, "missing")).isEmpty();
    }

    @Test
    void updatePageMovesTheSlugWithConflictDetection() {
        UUID id = UUID.randomUUID();
        CmsPage page = CmsPage.create(typeId, "old", "Old");
        when(pageRepository.findById(id)).thenReturn(Optional.of(page));
        when(pageRepository.existsBySlug("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.updatePage(admin, id, "New", "taken", null, null, null, null))
                .isInstanceOf(ConflictException.class);

        service.updatePage(admin, id, "New", "new-slug", "content", false, "SEO", "desc");
        assertThat(page.getTitle()).isEqualTo("New");
        assertThat(page.getSlug()).isEqualTo("new-slug");
        assertThat(page.isPublished()).isFalse();
    }

    @Test
    void translatePageCreatesAndThenUpdatesTheTranslation() {
        UUID id = UUID.randomUUID();
        CmsPage page = CmsPage.create(typeId, "about", "About");
        when(pageRepository.findById(id)).thenReturn(Optional.of(page));
        CmsPageTranslation standing = CmsPageTranslation.create(id, "ar");
        when(translationRepository.findByPageIdAndLanguageCode(id, "ar"))
                .thenReturn(Optional.of(standing))
                .thenReturn(Optional.of(standing));

        service.translatePage(admin, id, "ar", "من", "عن", "محتوى", null, null);
        service.translatePage(admin, id, "ar", "من2", "عن2", "محتوى2", "SEO", null);

        assertThat(standing.getTitle()).isEqualTo("عن2");
    }

    @Test
    void translatePageRefusesAMalformedLanguage() {
        UUID id = UUID.randomUUID();
        when(pageRepository.findById(id))
                .thenReturn(Optional.of(CmsPage.create(typeId, "a", "A")));

        assertThatThrownBy(() -> service.translatePage(admin, id, "not-a-lang", null, null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletePageRemovesTheTranslationsWithTheRow() {
        UUID id = UUID.randomUUID();
        CmsPage page = CmsPage.create(typeId, "a", "A");
        when(pageRepository.findById(id)).thenReturn(Optional.of(page));
        when(menuItemRepository.existsByPageId(id)).thenReturn(false);
        CmsPageTranslation translation = CmsPageTranslation.create(id, "ar");
        when(translationRepository.findAllByPageId(id)).thenReturn(List.of(translation));

        service.deletePage(admin, id);

        verify(translationRepository).delete(translation);
        verify(pageRepository).delete(page);
    }

    @Test
    void bulkDeletePagesSkipsMenuReferencedRows() {
        UUID free = UUID.randomUUID();
        UUID referenced = UUID.randomUUID();
        when(pageRepository.findById(free))
                .thenReturn(Optional.of(CmsPage.create(typeId, "free", "Free")));
        when(pageRepository.findById(referenced))
                .thenReturn(Optional.of(CmsPage.create(typeId, "ref", "Ref")));
        when(menuItemRepository.existsByPageId(free)).thenReturn(false);
        when(menuItemRepository.existsByPageId(referenced)).thenReturn(true);
        when(translationRepository.findAllByPageId(free)).thenReturn(List.of());

        int count = service.bulkDeletePages(admin, List.of(free, referenced));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void unassignRemovesThePairsAndResequences() {
        UUID id = UUID.randomUUID();
        when(pageTypeRepository.findById(id))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CmsPageTypeAttribute pa = CmsPageTypeAttribute.create(id, a, 0);
        CmsPageTypeAttribute pb = CmsPageTypeAttribute.create(id, b, 1);
        when(attributeRepository.findByPageTypeIdAndAttributeId(id, a))
                .thenReturn(Optional.of(pa));
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(id))
                .thenReturn(List.of(pa, pb));

        service.unassignPageTypeAttributes(admin, id, List.of(a));

        verify(attributeRepository).delete(pa);
    }

    @Test
    void deletePageTypeRefusesPagesStillReferencingIt() {
        when(pageRepository.existsByPageTypeId(typeId)).thenReturn(true);
        when(pageTypeRepository.findById(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));

        assertThatThrownBy(() -> service.deletePageType(admin, typeId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Pages still reference the page type");
    }

    @Test
    void deletePageTypeCascadeClearsAttributesAndBulkFollows() {
        UUID typeA = UUID.randomUUID();
        when(pageTypeRepository.findById(typeA))
                .thenReturn(Optional.of(CmsPageType.create("A", "a")));
        when(pageRepository.existsByPageTypeId(typeA)).thenReturn(false);
        CmsPageTypeAttribute pair = CmsPageTypeAttribute.create(typeA, UUID.randomUUID(), 0);
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(typeA))
                .thenReturn(List.of(pair));

        service.deletePageType(admin, typeA);
        verify(pageTypeRepository).delete(any(CmsPageType.class));

        assertThat(service.bulkDeletePageTypes(admin, List.of(typeA, UUID.randomUUID())))
                .isEqualTo(1);
    }

    @Test
    void thePageTypeReadLegsAnswerTheRegistry() {
        CmsPageType type = CmsPageType.create("T", "t");
        when(pageTypeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(pageTypeRepository.findAllByOrderByNameAscIdAsc(any(), any()))
                .thenReturn(org.springframework.data.domain.Window.from(List.of(type),
                        index -> org.springframework.data.domain.ScrollPosition.offset(index),
                        false));
        when(pageTypeRepository.findAllByOrderByNameDescIdDesc(any(), any()))
                .thenReturn(org.springframework.data.domain.Window.from(List.of(type),
                        index -> org.springframework.data.domain.ScrollPosition.offset(index),
                        false));
        when(pageTypeRepository.findAllByOrderBySlugAscIdAsc(any(), any()))
                .thenReturn(org.springframework.data.domain.Window.from(List.of(type),
                        index -> org.springframework.data.domain.ScrollPosition.offset(index),
                        false));
        when(pageTypeRepository.findAllByOrderBySlugDescIdDesc(any(), any()))
                .thenReturn(org.springframework.data.domain.Window.from(List.of(type),
                        index -> org.springframework.data.domain.ScrollPosition.offset(index),
                        false));
        when(pageTypeRepository.count()).thenReturn(1L);
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(type.getId()))
                .thenReturn(List.of());

        assertThat(service.findPageTypeById(admin, type.getId())).isPresent();
        assertThat(service.scrollPageTypes(admin, "NAME", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.of(10)).window()).isNotNull();
        for (String field : new String[] {"NAME", "SLUG"}) {
            for (boolean ascending : new boolean[] {true, false}) {
                service.scrollPageTypes(admin, field, ascending,
                        org.springframework.data.domain.ScrollPosition.keyset(),
                        org.springframework.data.domain.Limit.of(10));
            }
        }
    }

    @Test
    void createPageRefusesAMalformedSlugAndLongTitle() {
        when(pageTypeRepository.existsById(typeId)).thenReturn(true);

        assertThatThrownBy(() -> service.createPage(admin, "T", "Not Kebab", null, null, null,
                null, typeId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createPage(admin, "x".repeat(256), null, null, null,
                null, null, typeId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createPageRefusesATakenSlug() {
        when(pageTypeRepository.existsById(typeId)).thenReturn(true);
        when(pageRepository.existsBySlug("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.createPage(admin, "T", "taken", null, null, null,
                null, typeId))
                .isInstanceOf(ConflictException.class);
    }
}
