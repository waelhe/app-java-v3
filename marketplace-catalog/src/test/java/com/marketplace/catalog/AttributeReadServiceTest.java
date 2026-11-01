package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributePage;
import com.marketplace.catalog.spi.AttributeSort;
import com.marketplace.catalog.spi.AttributeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the campaign-b3-a2b attribute read
 * roots): the owning use case's unit proof — the JUnit 5 + Mockito style the
 * module's own {@code ProductTypeServiceTest}/{@code CatalogServiceTest}
 * established (no Spring context: the {@code @PreAuthorize}/
 * {@code @Transactional} proxies are integration concerns proven by the app
 * module's real-stack tests; the business decisions the boundary owns are
 * proven HERE).
 *
 * <p>Coverage target: every method branch of {@link AttributeReadService}
 * reachable through its typed seam — both lookups (null key, unknown key,
 * found, and the bilingual {@code nameEn ?? nameAr} rendering), and the
 * in-memory keyset scroll in BOTH owning sorts and BOTH directions
 * (deterministic with the {@code id} tiebreaker), the keyset predicate
 * (cursor follow-through in both directions, the string-form {@code id}
 * parsing leg, the malformed-cursor rejections: a backward keyset, a cursor
 * keyed by the OTHER sort's property, a missing {@code id} key, an
 * unparseable {@code id} value), the limit legs (absent limit, an
 * under-collection limit with {@code hasNext}, an over-collection limit),
 * the empty collection, and the {@code totalCount} fact (the full
 * collection's size, never the page's).</p>
 */
class AttributeReadServiceTest {

    private CatalogAttributeDefinitionRepository repository;
    private AttributeReadService service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        repository = mock(CatalogAttributeDefinitionRepository.class);
        service = new AttributeReadService(repository);
        authentication = mock(Authentication.class);
    }

    private static CatalogAttributeDefinition definition(String code, String nameEn, String nameAr,
                                                         CatalogAttributeType valueType) {
        return CatalogAttributeDefinition.create(code, nameEn, nameAr, valueType, null);
    }

    // ------------------------------------------------------------------
    // findAttributeById / findAttributeBySlug — the lookup legs
    // ------------------------------------------------------------------

    @Test
    void nullIdAnswersEmptyWithoutTouchingTheRepository() {
        assertThat(service.findAttributeById(null, authentication)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void unknownButWellFormedIdAnswersEmpty() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThat(service.findAttributeById(missing, authentication)).isEmpty();
    }

    @Test
    void foundIdAnswersTheBoundedProjectionWithTheEnglishPrimaryName() {
        CatalogAttributeDefinition definition =
                definition("brand", "Brand", "العلامة", CatalogAttributeType.CHOICE);
        when(repository.findById(definition.getId())).thenReturn(Optional.of(definition));

        Optional<AttributeView> view = service.findAttributeById(definition.getId(), authentication);

        assertThat(view).isPresent();
        assertThat(view.get().id()).isEqualTo(definition.getId());
        assertThat(view.get().name()).isEqualTo("Brand");
        assertThat(view.get().slug()).isEqualTo("brand");
        assertThat(view.get().valueType()).isEqualTo(CatalogAttributeType.CHOICE);
    }

    @Test
    void foundIdAnswersTheArabicNameWhenTheEnglishNameIsAbsent() {
        // The bilingual row's own at-least-one guarantee: nameEn null renders
        // nameAr — the registered translation-surface divergence's model.
        CatalogAttributeDefinition definition =
                definition("beta", null, "سمة بيتا", CatalogAttributeType.TEXT);
        when(repository.findById(definition.getId())).thenReturn(Optional.of(definition));

        Optional<AttributeView> view = service.findAttributeById(definition.getId(), authentication);

        assertThat(view).isPresent();
        assertThat(view.get().name()).isEqualTo("سمة بيتا");
        assertThat(view.get().valueType()).isEqualTo(CatalogAttributeType.TEXT);
    }

    @Test
    void nullSlugAnswersEmptyWithoutTouchingTheRepository() {
        assertThat(service.findAttributeBySlug(null, authentication)).isEmpty();
        assertThat(service.findAttributeBySlug("   ", authentication)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void unknownSlugAnswersEmptyAndKnownSlugAnswersTheCodeLookup() {
        when(repository.findByCode("unknown-code")).thenReturn(Optional.empty());
        assertThat(service.findAttributeBySlug("unknown-code", authentication)).isEmpty();

        CatalogAttributeDefinition definition =
                definition("color", "Color", null, CatalogAttributeType.NUMBER);
        when(repository.findByCode("color")).thenReturn(Optional.of(definition));
        Optional<AttributeView> view = service.findAttributeBySlug("color", authentication);
        assertThat(view).isPresent();
        assertThat(view.get().slug()).isEqualTo("color");
        assertThat(view.get().name()).isEqualTo("Color");
        assertThat(view.get().valueType()).isEqualTo(CatalogAttributeType.NUMBER);
    }

    // ------------------------------------------------------------------
    // scrollAttributes — the sort validation legs
    // ------------------------------------------------------------------

    @Test
    void missingSortAnswersTheClassifiedValidationError() {
        assertThatThrownBy(() -> service.scrollAttributes(authentication, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("An attribute sort is required");
        assertThatThrownBy(() -> service.scrollAttributes(authentication,
                        new AttributeSort(null, true), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("An attribute sort is required");
    }

    @Test
    void sortFactoryRejectsANullField() {
        assertThatThrownBy(() -> AttributeSort.of(null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("An attribute sort field is required");
    }

    // ------------------------------------------------------------------
    // scrollAttributes — the deterministic ordering legs (both sorts,
    // both directions, the id tiebreaker)
    // ------------------------------------------------------------------

    @Test
    void nameAscendingScrollOrdersByRenderedNameThenId() {
        CatalogAttributeDefinition gamma = definition("p2-gamma", "Gamma", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", null, "سمة بيتا", CatalogAttributeType.CHOICE);
        when(repository.findAll()).thenReturn(List.of(gamma, alpha, beta));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true), ScrollPosition.keyset(), null);

        List<AttributeView> content = page.window().getContent();
        assertThat(content).extracting(AttributeView::name)
                .containsExactly("Alpha", "Gamma", "سمة بيتا");
        assertThat(content).extracting(AttributeView::slug)
                .containsExactly("p2-alpha", "p2-gamma", "p2-beta");
        assertThat(content).extracting(AttributeView::valueType)
                .containsExactly(CatalogAttributeType.TEXT, CatalogAttributeType.TEXT,
                        CatalogAttributeType.CHOICE);
    }

    @Test
    void nameDescendingScrollReversesTheWindow() {
        CatalogAttributeDefinition gamma = definition("p2-gamma", "Gamma", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha, gamma));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, false), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(AttributeView::name)
                .containsExactly("Gamma", "Alpha");
    }

    @Test
    void slugAscendingScrollOrdersByTheImmutableCodeThenId() {
        CatalogAttributeDefinition zulu = definition("p2-zulu", "Zulu", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition mike = definition("p2-mike", "Mike", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(zulu, mike));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.SLUG, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(AttributeView::slug)
                .containsExactly("p2-mike", "p2-zulu");
    }

    @Test
    void slugDescendingScrollReversesTheWindow() {
        CatalogAttributeDefinition zulu = definition("p2-zulu", "Zulu", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition mike = definition("p2-mike", "Mike", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(mike, zulu));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.SLUG, false), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(AttributeView::slug)
                .containsExactly("p2-zulu", "p2-mike");
    }

    @Test
    void equalSortKeysFallThroughToTheDeterministicIdTiebreaker() {
        // Two rows sharing the SAME rendered name: the smaller id leads the
        // ascending window — the deterministic ordering the SQL keyset
        // methods' own ...AscIdAsc shape guarantees.
        CatalogAttributeDefinition first = definition("tie-one", "Tie", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition second = definition("tie-two", "Tie", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(second, first));
        UUID expectedLeading = first.getId().compareTo(second.getId()) < 0
                ? first.getId() : second.getId();

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(AttributeView::id)
                .containsExactly(expectedLeading,
                        expectedLeading.equals(first.getId()) ? second.getId() : first.getId());
    }

    // ------------------------------------------------------------------
    // scrollAttributes — the keyset predicate legs
    // ------------------------------------------------------------------

    @Test
    void cursorFollowThroughAnswersOnlyElementsAfterTheCursorSortKey() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", "Beta", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition gamma = definition("p2-gamma", "Gamma", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(gamma, alpha, beta));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true),
                ScrollPosition.forward(Map.of("name", "Beta", "id", beta.getId())),
                null);

        assertThat(page.window().getContent()).extracting(AttributeView::name)
                .containsExactly("Gamma");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void cursorIdCarriedAsItsStringFormStillParses() {
        // The wire cursor may deliver the id as its canonical string form —
        // the service's own UUID.fromString branch.
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", "Beta", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha, beta));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true),
                ScrollPosition.forward(Map.of("name", "Alpha", "id", alpha.getId().toString())),
                null);

        assertThat(page.window().getContent()).extracting(AttributeView::name)
                .containsExactly("Beta");
    }

    @Test
    void keysetPredicateHonorsTheDescendingDirection() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", "Beta", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition gamma = definition("p2-gamma", "Gamma", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha, beta, gamma));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, false),
                ScrollPosition.forward(Map.of("name", "Gamma", "id", gamma.getId())),
                null);

        assertThat(page.window().getContent()).extracting(AttributeView::name)
                .containsExactly("Beta", "Alpha");
    }

    @Test
    void slugKeysetPredicateScrollsStrictlyAfterTheCursorCode() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", "Beta", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha, beta));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.SLUG, true),
                ScrollPosition.forward(Map.of("slug", "p2-alpha", "id", alpha.getId())),
                null);

        assertThat(page.window().getContent()).extracting(AttributeView::slug)
                .containsExactly("p2-beta");
    }

    @Test
    void cursorKeyedByTheOtherSortsPropertyIsRejected() {
        // A slug-keyed cursor on the name scroll: the sort property key is
        // absent, so the honest classified error answers — never a silent
        // full re-read.
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollAttributes(authentication,
                        AttributeSort.of(AttributeSort.Field.NAME, true),
                        ScrollPosition.forward(Map.of("slug", "p2-alpha", "id", UUID.randomUUID())),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void cursorMissingTheIdTiebreakerKeyIsRejected() {
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollAttributes(authentication,
                        AttributeSort.of(AttributeSort.Field.NAME, true),
                        ScrollPosition.forward(Map.of("name", "Alpha")),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void backwardKeysetScrollPositionIsRejected() {
        // The forward-only contract's own guard: a backward keyset carries
        // the right key names but the wrong direction — the honest
        // classified error answers.
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollAttributes(authentication,
                        AttributeSort.of(AttributeSort.Field.NAME, true),
                        ScrollPosition.backward(Map.of("name", "Alpha", "id", UUID.randomUUID())),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void malformedCursorIdValueAnswersTheValidationTaxonomy() {
        // The id key parses through UUID.fromString: a well-named but
        // unparseable value answers IllegalArgumentException — the same
        // VAL-001 taxonomy the boundary resolver maps.
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollAttributes(authentication,
                        AttributeSort.of(AttributeSort.Field.NAME, true),
                        ScrollPosition.forward(Map.of("name", "Alpha", "id", "not-a-uuid")),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // scrollAttributes — the limit, hasNext and totalCount legs
    // ------------------------------------------------------------------

    @Test
    void absentLimitAnswersTheWholeBoundedCollection() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", "Beta", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha, beta));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void boundedLimitAnswersThePrefixWindowWithAnHonestHasNext() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition beta = definition("p2-beta", "Beta", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition gamma = definition("p2-gamma", "Gamma", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(gamma, alpha, beta));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(2));

        Window<AttributeView> window = page.window();
        assertThat(window.getContent()).extracting(AttributeView::name)
                .containsExactly("Alpha", "Beta");
        assertThat(window.hasNext()).isTrue();
        // totalCount is the COLLECTION count, never the page's.
        assertThat(page.total()).isEqualTo(3);

        // The emitted positions carry each row's own (name, id) sort key —
        // the keyset the opaque cursor identifies.
        KeysetScrollPosition firstPosition = (KeysetScrollPosition) window.positionAt(0);
        assertThat(firstPosition.getKeys().get("name")).isEqualTo("Alpha");
        assertThat(firstPosition.getKeys().get("id")).isEqualTo(alpha.getId());
        KeysetScrollPosition secondPosition = (KeysetScrollPosition) window.positionAt(1);
        assertThat(secondPosition.getKeys().get("name")).isEqualTo("Beta");
        assertThat(secondPosition.getKeys().get("id")).isEqualTo(beta.getId());
    }

    @Test
    void overCollectionLimitAnswersTheWholeWindow() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.window().getContent()).hasSize(1);
        assertThat(page.window().hasNext()).isFalse();
    }

    @Test
    void emptyCollectionAnswersTheEmptyWindowWithAnHonestZeroTotal() {
        when(repository.findAll()).thenReturn(List.of());

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(5));

        assertThat(page.window().getContent()).isEmpty();
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.total()).isZero();
    }

    @Test
    void slugScrollEmitsSlugKeyedPositionsForItsOwnCursors() {
        CatalogAttributeDefinition alpha = definition("p2-alpha", "Alpha", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(alpha));

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.SLUG, true), ScrollPosition.keyset(), Limit.of(1));

        KeysetScrollPosition position = (KeysetScrollPosition) page.window().positionAt(0);
        assertThat(position.getKeys().get("slug")).isEqualTo("p2-alpha");
        assertThat(position.getKeys().get("id")).isEqualTo(alpha.getId());
        assertThat(position.getKeys().keySet())
                .containsExactlyInAnyOrder("slug", "id");
    }

    @Test
    void descendingTiebreakerLegOrdersEqualCodesByReverseId() {
        // Two rows sharing the SAME code on the slug sort: the DESC window
        // orders by reverse id — the descending tiebreaker branch.
        CatalogAttributeDefinition one = definition("tie-code", "One", null, CatalogAttributeType.TEXT);
        CatalogAttributeDefinition two = definition("tie-code", "Two", null, CatalogAttributeType.TEXT);
        when(repository.findAll()).thenReturn(List.of(one, two));
        UUID trailing = one.getId().compareTo(two.getId()) < 0 ? one.getId() : two.getId();
        UUID leading = trailing.equals(one.getId()) ? two.getId() : one.getId();

        AttributePage page = service.scrollAttributes(authentication,
                AttributeSort.of(AttributeSort.Field.SLUG, false), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(AttributeView::id)
                .containsExactly(leading, trailing);
        assertThat(page.window().getContent())
                .isSortedAccordingTo(Comparator.comparing(AttributeView::id).reversed());
    }

    @Test
    void lookupLegsNeverConsultTheWholeCollectionSeam() {
        // The single-row lookups read by id and by code only — never the
        // whole-table seam the scroll owns.
        CatalogAttributeDefinition definition =
                definition("brand", "Brand", null, CatalogAttributeType.CHOICE);
        when(repository.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(repository.findByCode("brand")).thenReturn(Optional.of(definition));

        service.findAttributeById(definition.getId(), authentication);
        service.findAttributeBySlug("brand", authentication);

        verify(repository, never()).findAll();
    }
}
