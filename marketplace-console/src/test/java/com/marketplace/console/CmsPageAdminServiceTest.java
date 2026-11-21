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
import static org.mockito.Mockito.never;
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
        // The 2026-11-06 #810 round review: the creation leg's type
        // reference serializes on the locked load (findByIdForUpdate).
        when(pageTypeRepository.findByIdForUpdate(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        when(pageRepository.existsBySlug("about-us")).thenReturn(false);

        var view = service.createPage(admin, "About Us", null, "{\"blocks\":[]}", true, null, null, typeId);

        assertThat(view.slug()).isEqualTo("about-us");
        assertThat(view.published()).isTrue();
        verify(pageRepository).save(any(CmsPage.class));
    }

    @Test
    void createPageRefusesAnUnknownType() {
        when(pageTypeRepository.findByIdForUpdate(typeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPage(admin, "T", "t", null, null, null, null, typeId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletePageRefusesAMenuReferencedTarget() {
        UUID id = UUID.randomUUID();
        when(pageRepository.findByIdForUpdate(id))
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
        when(pageTypeRepository.findByIdForUpdate(typeId))
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
        when(pageTypeRepository.findByIdForUpdate(typeId))
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
        when(pageTypeRepository.findByIdForUpdate(typeId))
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
        when(pageTypeRepository.findByIdForUpdate(id))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        when(pageTypeRepository.existsBySlug("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.updatePageType(admin, id, null, "taken", null, null))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * B23 (IDP-05 — the 2026-11-03 adversarial review, review-pr-806): the
     * FULL admin sweep — every public leg of CmsPageAdminService, invoked with a
     * CONSUMER caller and inert arguments, must answer Spring Security's
     * own AccessDeniedException BEFORE any argument is touched (the
     * programmatic requireAdmin gate leads every leg; a future leg that
     * forgets it fails here on its first null-argument symptom instead).
     * A primitive parameter cannot carry null — Method.invoke would fail
     * its unboxing BEFORE the leg's gate could answer — so primitive slots
     * ride their zero-value default (see {@link #inert(Class)}).
     */
    @Test
    void everyLegIsAdminGated() {
        Authentication consumer = new UsernamePasswordAuthenticationToken("m@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        for (java.lang.reflect.Method leg : CmsPageAdminService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(leg.getModifiers()) || leg.isSynthetic()) {
                continue;
            }
            Object[] args = new Object[leg.getParameterCount()];
            if (args.length > 0) {
                args[0] = consumer;
            }
            for (int i = 1; i < args.length; i++) {
                args[i] = inert(leg.getParameterTypes()[i]);
            }
            assertThatThrownBy(() -> leg.invoke(service, args))
                    .as("%s.%s must refuse a CONSUMER caller at its gate", "CmsPageAdminService", leg.getName())
                    .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                    .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

    /**
     * The reflection harness's inert argument for one parameter slot: a
     * primitive slot cannot carry null — Method.invoke would fail the
     * unboxing with IllegalArgumentException BEFORE the leg's gate could
     * answer — so it rides its zero-value default instead (the CI round on
     * the B23 sweep: bulkUpdateCustomers' {@code boolean ignoreFailed}).
     * Every reference slot stays null: a leg that touches its arguments
     * before the gate still fails on its first null-argument symptom,
     * exactly as the sweep promises.
     */
    private static Object inert(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        throw new IllegalArgumentException("Unknown primitive type: " + type);
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
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the branch MAPPING is now verified per field —
        // PUBLICATION_DATE rides the createdAt columns per the registered
        // DECISION-10 divergence (a single hasNext assertion could not
        // tell the branches apart), and an UNKNOWN field answers the
        // classified validation error, never a silent fallback to the
        // default sort.
        org.mockito.Mockito.verify(pageRepository).findAllByOrderBySlugAscIdAsc(any(), any());
        org.mockito.Mockito.verify(pageRepository).findAllByOrderBySlugDescIdDesc(any(), any());
        org.mockito.Mockito.verify(pageRepository).findAllByOrderByTitleAscIdAsc(any(), any());
        org.mockito.Mockito.verify(pageRepository).findAllByOrderByTitleDescIdDesc(any(), any());
        org.mockito.Mockito.verify(pageRepository, org.mockito.Mockito.times(2))
                .findAllByOrderByCreatedAtAscIdAsc(any(), any());
        org.mockito.Mockito.verify(pageRepository, org.mockito.Mockito.times(2))
                .findAllByOrderByCreatedAtDescIdDesc(any(), any());
        assertThatThrownBy(() -> service.scrollPages(admin, "RATING", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported pages sort field");
    }

    /**
     * The 2026-11-05 #810 round review: the forward-only scroll contract —
     * an empty BACKWARD keyset position (Spring Data Commons defines
     * isInitial() as keys.isEmpty(), direction-independent) answers the
     * classified validation error, and the page-types family carries the
     * same two laws.
     */
    @Test
    void theScrollLegsRejectBackwardPositionsAndUnknownSortFields() {
        // Spring Data Commons' own reverse(): the empty BACKWARD keyset
        // position (isInitial() is keys.isEmpty() — direction-blind).
        org.springframework.data.domain.KeysetScrollPosition backward =
                org.springframework.data.domain.ScrollPosition.keyset().reverse();

        // Every leg answers the classified error BEFORE the repository
        // (no stubs needed — the verdict precedes the read).
        assertThatThrownBy(() -> service.scrollPages(admin, null, true, backward,
                org.springframework.data.domain.Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward-only");
        assertThatThrownBy(() -> service.scrollPageTypes(admin, null, true, backward,
                org.springframework.data.domain.Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward-only");
        assertThatThrownBy(() -> service.scrollPageTypes(admin, "RATING", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported page types sort field");
    }

    /**
     * The 2026-11-05 #810 round review: the reorder's list contract — a
     * missing deltas list or a length mismatch answers the classified
     * validation error before any database read, and the clamp arithmetic
     * runs in long (from + Integer.MAX_VALUE no longer overflows into a
     * wrongly-accepted 0).
     */
    @Test
    void reorderPageTypeAttributesValidatesTheListsAndClampsInLong() {
        CmsPageType type = CmsPageType.create("T", "t");
        UUID typeId = type.getId();
        when(pageTypeRepository.findByIdForUpdate(typeId)).thenReturn(Optional.of(type));
        CmsPageTypeAttribute first = CmsPageTypeAttribute.create(typeId, UUID.randomUUID(), 0);
        CmsPageTypeAttribute second = CmsPageTypeAttribute.create(typeId, UUID.randomUUID(), 1);
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of(first, second));

        // A missing/mismatched deltas list answers the classified error.
        assertThatThrownBy(() -> service.reorderPageTypeAttributes(admin, typeId,
                List.of(first.getAttributeId()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one entry per attribute");
        assertThatThrownBy(() -> service.reorderPageTypeAttributes(admin, typeId,
                List.of(first.getAttributeId()), List.of(1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one entry per attribute");

        // The overflow clamp: Integer.MAX_VALUE delta on position 0 — the
        // long sum clamps to the list's end (the item lands LAST), never
        // overflows to a negative the old int math read as 0.
        service.reorderPageTypeAttributes(admin, typeId,
                List.of(first.getAttributeId()), List.of(Integer.MAX_VALUE));
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(second.getPosition()).isEqualTo(0);
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

        service.updatePage(admin, id, "New", "new-slug", "{\"blocks\":[]}", false, "SEO", "desc");
        assertThat(page.getTitle()).isEqualTo("New");
        assertThat(page.getSlug()).isEqualTo("new-slug");
        assertThat(page.isPublished()).isFalse();
    }

    @Test
    void translatePageCreatesAndThenUpdatesTheTranslation() {
        UUID id = UUID.randomUUID();
        CmsPage page = CmsPage.create(typeId, "about", "About");
        // The 2026-11-08 #810 round-5 review: the locked page load (the
        // delete-vs-translation serialization).
        when(pageRepository.findByIdForUpdate(id)).thenReturn(Optional.of(page));
        CmsPageTranslation standing = CmsPageTranslation.create(id, "ar");
        when(translationRepository.findByPageIdAndLanguageCode(id, "ar"))
                .thenReturn(Optional.of(standing))
                .thenReturn(Optional.of(standing));

        service.translatePage(admin, id, "ar", "من", "عن", "{\"blocks\":[]}", null, null);
        service.translatePage(admin, id, "ar", "من2", "عن2",
                "{\"blocks\":[{\"type\":\"paragraph\"}]}", "SEO", null);

        assertThat(standing.getTitle()).isEqualTo("عن2");
    }

    @Test
    void translatePageRefusesAMalformedLanguage() {
        UUID id = UUID.randomUUID();
        when(pageRepository.findByIdForUpdate(id))
                .thenReturn(Optional.of(CmsPage.create(typeId, "a", "A")));

        assertThatThrownBy(() -> service.translatePage(admin, id, "not-a-lang", null, null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletePageRemovesTheTranslationsWithTheRow() {
        UUID id = UUID.randomUUID();
        CmsPage page = CmsPage.create(typeId, "a", "A");
        when(pageRepository.findByIdForUpdate(id)).thenReturn(Optional.of(page));
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
        when(pageRepository.findByIdForUpdate(free))
                .thenReturn(Optional.of(CmsPage.create(typeId, "free", "Free")));
        when(pageRepository.findByIdForUpdate(referenced))
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
        when(pageTypeRepository.findByIdForUpdate(id))
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
        when(pageTypeRepository.findByIdForUpdate(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));

        assertThatThrownBy(() -> service.deletePageType(admin, typeId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Pages still reference the page type");
    }

    @Test
    void deletePageTypeCascadeClearsAttributesAndBulkFollows() {
        UUID typeA = UUID.randomUUID();
        when(pageTypeRepository.findByIdForUpdate(typeA))
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
        when(pageTypeRepository.findByIdForUpdate(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));

        assertThatThrownBy(() -> service.createPage(admin, "T", "Not Kebab", null, null, null,
                null, typeId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createPage(admin, "x".repeat(256), null, null, null,
                null, null, typeId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createPageRefusesATakenSlug() {
        // The 2026-11-06 #810 round review: the creation leg's locked type
        // load (the sibling createPage tests' own stub shape).
        when(pageTypeRepository.findByIdForUpdate(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        when(pageRepository.existsBySlug("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.createPage(admin, "T", "taken", null, null, null,
                null, typeId))
                .isInstanceOf(ConflictException.class);
    }
    // ==== The 2026-11-08 #810 round-5 review additions ====

    /**
     * The round-5 overflow nitpick (T1): the reorder clamp catches the
     * overflow from a POSITIVE index too — THREE attributes, the MIDDLE
     * one moves with Integer.MAX_VALUE, and must land at the END (index 0
     * + MAX_VALUE never overflows, so the existing clamp test alone could
     * not tell int math from long math).
     */
    @Test
    void reorderPageTypeAttributesClampsAPositiveIndexOverflowToTheEnd() {
        CmsPageType type = CmsPageType.create("T", "t");
        UUID typeId = type.getId();
        when(pageTypeRepository.findByIdForUpdate(typeId)).thenReturn(Optional.of(type));
        CmsPageTypeAttribute first = CmsPageTypeAttribute.create(typeId, UUID.randomUUID(), 0);
        CmsPageTypeAttribute middle = CmsPageTypeAttribute.create(typeId, UUID.randomUUID(), 1);
        CmsPageTypeAttribute last = CmsPageTypeAttribute.create(typeId, UUID.randomUUID(), 2);
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of(first, middle, last));

        service.reorderPageTypeAttributes(admin, typeId,
                List.of(middle.getAttributeId()), List.of(Integer.MAX_VALUE));

        assertThat(first.getPosition()).isZero();
        assertThat(last.getPosition()).isEqualTo(1);
        assertThat(middle.getPosition()).isEqualTo(2);
    }

    /**
     * The round-5 translation nitpick (T3): the ABSENT translation leg —
     * a fresh row is created with the page's id and the requested
     * language (the existing test only exercised the standing row).
     */
    @Test
    void translatePageCreatesTheTranslationWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(pageRepository.findByIdForUpdate(id))
                .thenReturn(Optional.of(CmsPage.create(typeId, "about", "About")));
        when(translationRepository.findByPageIdAndLanguageCode(id, "ar"))
                .thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<CmsPageTranslation> captured =
                org.mockito.ArgumentCaptor.forClass(CmsPageTranslation.class);

        service.translatePage(admin, id, "ar", "من", "عن", "{\"blocks\":[]}", null, null);

        verify(translationRepository).save(captured.capture());
        assertThat(captured.getValue().getPageId()).isEqualTo(id);
        assertThat(captured.getValue().getLanguageCode()).isEqualTo("ar");
    }

    /**
     * The round-5 boundary (#1): the page slug's own VARCHAR(255) bound —
     * the derived slug of a long title answers the classified validation
     * error, never the flush's raw storage error.
     */
    @Test
    void createPageRefusesAnOverlongDerivedSlug() {
        when(pageTypeRepository.findByIdForUpdate(typeId))
                .thenReturn(Optional.of(CmsPageType.create("T", "t")));
        // A 255-repeat of U+0130 slugifies beyond the column's bound.
        String longTitle = "\u0130".repeat(255);

        assertThatThrownBy(() -> service.createPage(admin, longTitle, null,
                null, null, null, null, typeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
        verify(pageRepository, never()).save(any(CmsPage.class));
    }

}
