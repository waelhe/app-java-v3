package com.marketplace.catalog;

import com.marketplace.catalog.spi.CategoryPage;
import com.marketplace.catalog.spi.CategorySort;
import com.marketplace.catalog.spi.CategoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — packet campaign-b4-a, the
 * {@code category}/{@code categories} read roots): the owning use case's
 * unit proof — the JUnit 5 + Mockito style the module's own
 * {@code AttributeReadServiceTest} established (no Spring context: the
 * {@code @Transactional} proxies are integration concerns proven by the
 * app module's real-stack tests; the business decisions the boundary
 * owns are proven HERE).
 *
 * <p>Coverage target: every method branch of {@link CategoryReadService}
 * reachable through its typed seam — both lookups (null key, unknown
 * key, found, and the bilingual {@code nameEn ?? nameAr} rendering with
 * the neither-name null leg), and the in-memory keyset scroll in the
 * owning NAME sort BOTH directions (deterministic with the {@code id}
 * tiebreaker), the keyset predicate (cursor follow-through in both
 * directions, the malformed-cursor rejections: a backward keyset, a
 * cursor keyed by another property, a missing {@code id} key, an
 * unparseable {@code id} value), the limit legs (absent limit, an
 * under-collection limit with {@code hasNext}, an over-collection
 * limit), the empty registry, the nameless-row empty-string ordering
 * key, and the {@code totalCount} fact (the full registry's size, never
 * the page's).</p>
 */
class CategoryReadServiceTest {

    private CategoryRepository repository;
    private CategoryReadService service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        repository = mock(CategoryRepository.class);
        service = new CategoryReadService(repository);
        authentication = mock(Authentication.class);
    }

    private static Category row(String code, String nameEn, String nameAr, int position) {
        // The registry's own factory (the entity's documented write shape).
        return Category.create(code, nameEn, nameAr, position);
    }

    /** The registry factory assigns a random id — keyset-tiebreak proofs need FIXED ids. */
    private static Category withId(Category row, UUID id) {
        try {
            Field field = Category.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(row, id);
            return row;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("test id stamp failed", exception);
        }
    }

    private static CategoryView viewOf(Category row) {
        String name = row.getNameEn() != null ? row.getNameEn() : row.getNameAr();
        return new CategoryView(row.getId(), name, row.getNameAr(), row.getCode(), row.getPosition());
    }

    // ------------------------------------------------------------------
    // findCategoryById / findCategoryBySlug — the bounded lookups
    // ------------------------------------------------------------------

    @Test
    void nullIdAnswersEmptyWithoutTouchingTheRepository() {
        assertThat(service.findCategoryById(null, authentication)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void unknownIdAnswersEmptyNeverAnError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThat(service.findCategoryById(unknown, authentication)).isEmpty();
    }

    @Test
    void knownIdAnswersTheBoundedProjection() {
        Category stay = row("stay", "Stay", "إقامة", 1);
        when(repository.findById(stay.getId())).thenReturn(Optional.of(stay));
        Optional<CategoryView> found = service.findCategoryById(stay.getId(), authentication);
        assertThat(found).contains(viewOf(stay));
        assertThat(found.get().name()).isEqualTo("Stay");
        assertThat(found.get().nameAr()).isEqualTo("إقامة");
        assertThat(found.get().slug()).isEqualTo("stay");
        assertThat(found.get().position()).isEqualTo(1);
    }

    @Test
    void bilingualRowWithoutEnglishRendersItsArabicPrimaryName() {
        Category arOnly = row("b4a-ar", null, "بيتا للاختبار", 2);
        when(repository.findById(arOnly.getId())).thenReturn(Optional.of(arOnly));
        assertThat(service.findCategoryById(arOnly.getId(), authentication))
                .contains(new CategoryView(arOnly.getId(), "بيتا للاختبار", "بيتا للاختبار", "b4a-ar", 2));
    }

    @Test
    void nullAndBlankSlugAnswerEmptyWithoutTouchingTheRepository() {
        assertThat(service.findCategoryBySlug(null, authentication)).isEmpty();
        assertThat(service.findCategoryBySlug("   ", authentication)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void unknownSlugAnswersEmptyNeverAnError() {
        when(repository.findByCode("no-such-code")).thenReturn(Optional.empty());
        assertThat(service.findCategoryBySlug("no-such-code", authentication)).isEmpty();
    }

    @Test
    void knownSlugAnswersTheBoundedProjectionThroughTheOwnerSeam() {
        Category stay = row("stay", "Stay", "إقامة", 1);
        when(repository.findByCode("stay")).thenReturn(Optional.of(stay));
        assertThat(service.findCategoryBySlug("stay", authentication)).contains(viewOf(stay));
        verify(repository).findByCode("stay");
    }

    // ------------------------------------------------------------------
    // scrollCategories — the in-memory forward-only keyset scroll
    // ------------------------------------------------------------------

    @Test
    void nullSortAnswersTheClassifiedError() {
        assertThatThrownBy(() -> service.scrollCategories(authentication, null,
                        ScrollPosition.keyset(), Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A category sort is required");
    }

    @Test
    void emptyRegistryAnswersTheEmptyWindowWithZeroTotal() {
        when(repository.findAll()).thenReturn(List.of());
        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));
        assertThat(page.window().getContent()).isEmpty();
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.total()).isZero();
    }

    @Test
    void nameAscendingScrollOrdersDeterministicallyWithTheIdTiebreaker() {
        List<Category> rows = new ArrayList<>();
        rows.add(row("zulu", "Zulu", null, 9));
        rows.add(row("alpha", "Alpha", null, 1));
        // The tiebreaker leg: two rows sharing ONE rendered name differ
        // only by id — the deterministic id ordering must decide.
        Category tieA = row("tie-a", "Tie", null, 5);
        Category tieB = row("tie-b", "Tie", null, 6);
        rows.add(tieA);
        rows.add(tieB);
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));

        List<CategoryView> content = page.window().getContent();
        assertThat(content).extracting(CategoryView::slug)
                .containsExactly("alpha", tieA.getId().compareTo(tieB.getId()) < 0 ? "tie-a" : "tie-b",
                        tieA.getId().compareTo(tieB.getId()) < 0 ? "tie-b" : "tie-a", "zulu");
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.window().hasNext()).isFalse();
    }

    @Test
    void nameDescendingScrollReversesTheWindow() {
        List<Category> rows = List.of(
                row("zulu", "Zulu", null, 9),
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5));
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, false), ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.window().getContent()).extracting(CategoryView::slug)
                .containsExactly("zulu", "mike", "alpha");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void namelessRowOrdersByTheEmptyStringKeyAndKeepsItsHonestNullName() {
        List<Category> rows = List.of(
                row("zulu", "Zulu", null, 9),
                // Neither bilingual name: the ORDERING key is the empty
                // string (first in ascending), the WIRE name stays null.
                row("nameless", null, null, 3),
                row("alpha", "Alpha", null, 1));
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.window().getContent()).extracting(CategoryView::slug)
                .containsExactly("nameless", "alpha", "zulu");
        assertThat(page.window().getContent()).extracting(CategoryView::name)
                .containsExactly(null, "Alpha", "Zulu");
        // The nameless row's own cursor position carries the empty-string
        // ordering key — never a null value (the total-key contract).
        ScrollPosition first = page.window().positionAt(0);
        assertThat(((KeysetScrollPosition) first).getKeys().get("name")).isEqualTo("");
    }

    @Test
    void underCollectionLimitAnswersHasNextTrueAndThePageSlice() {
        List<Category> rows = List.of(
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5),
                row("zulu", "Zulu", null, 9));
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(2));

        assertThat(page.window().getContent()).extracting(CategoryView::slug)
                .containsExactly("alpha", "mike");
        assertThat(page.window().hasNext()).isTrue();
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void overCollectionLimitAnswersTheWholeCollectionWithoutHasNext() {
        List<Category> rows = List.of(
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5));
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(50));

        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void absentLimitAnswersTheWholeRegistry() {
        List<Category> rows = List.of(
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5));
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.total()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // the keyset predicate — cursor follow-through in both directions
    // ------------------------------------------------------------------

    @Test
    void forwardCursorFollowThroughContinuesStrictlyAfterTheCursorKey() {
        List<Category> rows = List.of(
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5),
                row("zulu", "Zulu", null, 9));
        when(repository.findAll()).thenReturn(rows);

        Category first = rows.get(0);
        KeysetScrollPosition afterAlpha = ScrollPosition.forward(
                Map.of("name", "Alpha", "id", first.getId()));

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), afterAlpha, Limit.of(10));

        assertThat(page.window().getContent()).extracting(CategoryView::slug)
                .containsExactly("mike", "zulu");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void descendingCursorFollowThroughContinuesStrictlyAfterTheCursorKey() {
        List<Category> rows = List.of(
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5),
                row("zulu", "Zulu", null, 9));
        when(repository.findAll()).thenReturn(rows);

        Category last = rows.get(2);
        KeysetScrollPosition afterZuluDescending = ScrollPosition.forward(
                Map.of("name", "Zulu", "id", last.getId()));

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, false), afterZuluDescending, Limit.of(10));

        assertThat(page.window().getContent()).extracting(CategoryView::slug)
                .containsExactly("mike", "alpha");
    }

    @Test
    void theTiebreakerDecidesWhenTheCursorSharesTheSortKey() {
        // FIXED ids (the factory's random ids made this proof flaky — the
        // tiebreak decision must be deterministic, not a coin flip):
        // under the shared NAME key, strictly-after means the id order.
        UUID lesserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID greaterId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Category tieA = withId(row("tie-a", "Tie", null, 5), lesserId);
        Category tieB = withId(row("tie-b", "Tie", null, 6), greaterId);
        List<Category> rows = List.of(tieA, tieB);
        when(repository.findAll()).thenReturn(rows);

        // The cursor IS the lesser key: only tieB (the strictly-greater id
        // under the same name) may follow.
        KeysetScrollPosition afterTheLesser = ScrollPosition.forward(
                Map.of("name", "Tie", "id", lesserId));

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), afterTheLesser, Limit.of(10));

        assertThat(page.window().getContent()).extracting(CategoryView::slug)
                .containsExactly("tie-b");

        // The GREATER key is the last row under the shared name — nothing
        // strictly follows it (the honest empty page, never a wrap-around
        // and never the cursor's own row).
        KeysetScrollPosition afterTheGreater = ScrollPosition.forward(
                Map.of("name", "Tie", "id", greaterId));

        CategoryPage tail = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), afterTheGreater, Limit.of(10));

        assertThat(tail.window().getContent()).isEmpty();
    }

    // ------------------------------------------------------------------
    // the malformed-cursor rejections — the honest classified errors
    // ------------------------------------------------------------------

    @Test
    void backwardKeysetIsRejected() {
        when(repository.findAll()).thenReturn(List.of(row("alpha", "Alpha", null, 1)));
        KeysetScrollPosition backward = ScrollPosition.backward(
                Map.of("name", "Alpha", "id", UUID.randomUUID()));
        assertThatThrownBy(() -> service.scrollCategories(authentication,
                        CategorySort.of(CategorySort.Field.NAME, true), backward, Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void cursorKeyedByAnotherPropertyIsRejected() {
        when(repository.findAll()).thenReturn(List.of(row("alpha", "Alpha", null, 1)));
        KeysetScrollPosition wrongProperty = ScrollPosition.forward(
                Map.of("slug", "alpha", "id", UUID.randomUUID()));
        assertThatThrownBy(() -> service.scrollCategories(authentication,
                        CategorySort.of(CategorySort.Field.NAME, true), wrongProperty, Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void cursorMissingTheIdKeyIsRejected() {
        when(repository.findAll()).thenReturn(List.of(row("alpha", "Alpha", null, 1)));
        KeysetScrollPosition noId = ScrollPosition.forward(
                Map.of("name", "Alpha"));
        assertThatThrownBy(() -> service.scrollCategories(authentication,
                        CategorySort.of(CategorySort.Field.NAME, true), noId, Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void cursorWithAnUnparseableIdValueIsRejected() {
        when(repository.findAll()).thenReturn(List.of(row("alpha", "Alpha", null, 1)));
        KeysetScrollPosition badId = ScrollPosition.forward(
                Map.of("name", "Alpha", "id", "not-a-uuid"));
        assertThatThrownBy(() -> service.scrollCategories(authentication,
                        CategorySort.of(CategorySort.Field.NAME, true), badId, Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    // ------------------------------------------------------------------
    // the emitted window positions — the cursor vocabulary the
    // boundary's envelope and gate share
    // ------------------------------------------------------------------

    @Test
    void windowPositionsCarryTheSortKeyAndIdPerElement() {
        Category alpha = row("alpha", "Alpha", null, 1);
        Category zulu = row("zulu", "Zulu", null, 9);
        when(repository.findAll()).thenReturn(List.of(alpha, zulu));

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));

        KeysetScrollPosition first = (KeysetScrollPosition) page.window().positionAt(0);
        KeysetScrollPosition second = (KeysetScrollPosition) page.window().positionAt(1);
        assertThat(first.getKeys()).containsEntry("name", "Alpha").containsEntry("id", alpha.getId());
        assertThat(second.getKeys()).containsEntry("name", "Zulu").containsEntry("id", zulu.getId());
        assertThat(first.scrollsForward()).isTrue();
        assertThat(second.scrollsForward()).isTrue();
    }

    @Test
    void totalCountIsTheRegistrySizeNeverThePageSize() {
        List<Category> rows = List.of(
                row("alpha", "Alpha", null, 1),
                row("mike", "Mike", null, 5),
                row("zulu", "Zulu", null, 9));
        when(repository.findAll()).thenReturn(rows);

        CategoryPage page = service.scrollCategories(authentication,
                CategorySort.of(CategorySort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(1));

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.window().getContent()).hasSize(1);
    }
}
