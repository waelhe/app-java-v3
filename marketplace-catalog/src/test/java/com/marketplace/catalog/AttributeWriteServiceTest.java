package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeBulkUpdateItem;
import com.marketplace.catalog.spi.AttributeCreateCommand;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.AttributeUpdateCommand;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeCreate}, {@code attributeUpdate},
 * {@code attributeDelete} and {@code attributeBulkDelete} mutation
 * roots, the agent-2 packet): the owning write use case's unit proof —
 * the JUnit 5 + Mockito style the module's own
 * {@code ProductTypeWriteServiceTest} established (no Spring context:
 * the {@code @PreAuthorize} proxy is an integration concern proven by
 * the app module's real-stack tests; the business decisions the
 * boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link AttributeWriteService} reachable through its typed seam —
 * the create's full validation ladder (blank/oversized name,
 * missing/invalid/duplicate slug, the missing value shape, the happy
 * path's row factory), the update's partial-preserve semantics (the
 * empty no-op, null fields preserve, the blank-name INVALID, the
 * immutable-slug refusals — a different code, another row's code, an
 * explicit blank — and the row's-own-code idempotent re-send, the
 * NOT_FOUND absence), the single delete's NOT_FOUND and soft-delete
 * happy path, and the bulk delete's honest count (unknown ids
 * skipped, never fabricated).</p>
 */
class AttributeWriteServiceTest {

    private CatalogAttributeDefinitionRepository repository;
    private CatalogAttributeOptionRepository optionRepository;
    private ProductTypeAttributeAssignmentRepository assignmentRepository;
    private ProductVariantAttributeValueRepository variantAttributeValues;
    private ProductAttributeValueRepository productAttributeValues;
    private AttributeWriteSpi service;

    @BeforeEach
    void setUp() {
        repository = mock(CatalogAttributeDefinitionRepository.class);
        optionRepository = mock(CatalogAttributeOptionRepository.class);
        assignmentRepository = mock(ProductTypeAttributeAssignmentRepository.class);
        // The 2026-11-06 #808 round: the delete guards' live-variant probe
        // answers NO references by default (each test overrides it).
        variantAttributeValues = mock(ProductVariantAttributeValueRepository.class);
        org.mockito.Mockito.lenient()
                .when(variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        // The 2026-11-06 #808 round-3: the PRODUCT-scope twin answers NO
        // references by default (each test overrides it).
        productAttributeValues = mock(ProductAttributeValueRepository.class);
        org.mockito.Mockito.lenient()
                .when(productAttributeValues.existsLiveProductValueForAssignmentsOrOptions(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        service = new AttributeWriteService(repository, optionRepository, assignmentRepository,
                variantAttributeValues, productAttributeValues);
    }

    private static CatalogAttributeDefinition row(String code, String nameEn) {
        return CatalogAttributeDefinition.create(code, nameEn, null, CatalogAttributeType.TEXT, null);
    }

    // ------------------------------------------------------------------
    // createAttribute — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void blankNameAnswersTheRequiredDomainError() {
        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("  ", "fresh-attr", CatalogAttributeType.TEXT));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly(new AttributeMutationError(
                "name", AttributeMutationErrorCode.REQUIRED, "An attribute name is required"));
        verify(repository, never()).save(any());
    }

    @Test
    void oversizedNameAnswersTheInvalidDomainError() {
        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("x".repeat(101), "fresh-attr", CatalogAttributeType.TEXT));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "name");
    }

    @Test
    void missingSlugAnswersTheRequiredDomainError() {
        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("Material", null, CatalogAttributeType.TEXT));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.REQUIRED, "slug");
    }

    @Test
    void invalidSlugShapeAnswersTheInvalidDomainError() {
        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("Material", "Not_A_Slug", CatalogAttributeType.TEXT));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateSlugAnswersTheAlreadyExistsDomainError() {
        when(repository.findByCode("taken")).thenReturn(Optional.of(row("taken", "Taken")));

        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("Material", "taken", CatalogAttributeType.TEXT));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.ALREADY_EXISTS, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void missingValueTypeAnswersTheRequiredDomainErrorOnInputType() {
        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("Material", "fresh-attr", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.REQUIRED, "inputType");
        verify(repository, never()).save(any());
    }

    @Test
    void happyCreateSavesTheRowAndAnswersTheProjection() {
        when(repository.findByCode("fresh-attr")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeMutationResult result = service.createAttribute(
                new AttributeCreateCommand("Material", "fresh-attr", CatalogAttributeType.CHOICE));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().name()).isEqualTo("Material");
        assertThat(result.attribute().slug()).isEqualTo("fresh-attr");
        assertThat(result.attribute().valueType()).isEqualTo(CatalogAttributeType.CHOICE);
        assertThat(result.attribute().id()).isNotNull();
        verify(repository).save(any(CatalogAttributeDefinition.class));
    }

    // ------------------------------------------------------------------
    // updateAttribute — the partial-preserve semantics
    // ------------------------------------------------------------------

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        AttributeMutationResult result = service.updateAttribute(unknown,
                new AttributeUpdateCommand("New", null));

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void emptyCommandAnswersTheCurrentProjectionWithoutASave() {
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand(null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().slug()).isEqualTo("keep-attr");
        assertThat(result.attribute().name()).isEqualTo("Kept");
        verify(repository, never()).save(any());
    }

    @Test
    void providedFieldsReplaceAndNullsPreserve() {
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand("Refreshed", null));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().name()).isEqualTo("Refreshed");
        assertThat(result.attribute().slug()).isEqualTo("keep-attr");
        assertThat(result.attribute().valueType()).isEqualTo(CatalogAttributeType.TEXT);
        verify(repository).save(any(CatalogAttributeDefinition.class));
    }

    @Test
    void blankNameAnswersTheInvalidDomainError() {
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand("   ", null));

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "name");
        verify(repository, never()).save(any());
    }

    @Test
    void slugChangeToADifferentCodeAnswersTheImmutableDomainError() {
        // The entity's own law: "Type and code are stable identity;
        // only display metadata can be amended" — a different slug
        // (whether free or taken by another row) is the classified
        // INVALID refusal, never a silent drop and never an
        // unauthorized move.
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("fresh-name")).thenReturn(Optional.empty());

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand(null, "fresh-name"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "slug");
        assertThat(result.errors().get(0).message())
                .isEqualTo("An attribute slug is immutable; it cannot change after creation");
        assertThat(existing.getCode()).isEqualTo("keep-attr");
        verify(repository, never()).save(any());
    }

    @Test
    void slugChangeToAnotherRowsCodeAnswersTheImmutableDomainError() {
        // Even a slug owned by another row answers the immutable
        // refusal first — the code never moves, so the uniqueness
        // ladder below it is unreachable for a different code.
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("taken")).thenReturn(Optional.of(row("taken", "Taken")));

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand(null, "taken"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void slugChangeToTheRowsOwnCodeExecutes() {
        // The row's own code is EXCLUDED from the duplicate check — a
        // re-send of the current slug is a no-op, not a conflict.
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("keep-attr")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand("Refreshed", "keep-attr"));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().slug()).isEqualTo("keep-attr");
        verify(repository).save(any(CatalogAttributeDefinition.class));
    }

    @Test
    void blankSlugOnUpdateAnswersTheImmutableDomainError() {
        // An explicitly blank slug is a change attempt (to blank) —
        // the immutable refusal answers it, never a silent drop.
        CatalogAttributeDefinition existing = row("keep-attr", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        AttributeMutationResult result = service.updateAttribute(existing.getId(),
                new AttributeUpdateCommand(null, "   "));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void deleteRefusesWhenALiveVariantCarriesTheAttributesChildren() {
        // The 2026-11-06 #808 round review (Major · Data Integrity): the
        // attribute's assignments and options ARE the variant schema —
        // deleting them under live variant values leaves the values bound
        // to soft-deleted assignments and the keys computed on a dead
        // schema. The refusal is the honest verdict: variants migrate
        // first.
        CatalogAttributeDefinition existing = row("held-attr", "Held");
        when(repository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        org.mockito.Mockito.when(variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        AttributeMutationResult result = service.deleteAttribute(existing.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "id");
        verify(repository, never()).delete(any(CatalogAttributeDefinition.class));
    }

    @Test
    void deleteRefusesWhenALiveProductCarriesTheAttributesChildren() {
        // The 2026-11-06 #808 round-3 review (Major · Data Integrity):
        // the PRODUCT-scope population's twin of the variant refusal — a
        // live product's own value bound to the attribute's assignments
        // or options is the same orphan: the publish path's
        // re-validation reads the assignments and would strand the
        // product on a dead schema.
        CatalogAttributeDefinition existing = row("held-product-attr", "Held");
        when(repository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        org.mockito.Mockito.when(productAttributeValues.existsLiveProductValueForAssignmentsOrOptions(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        AttributeMutationResult result = service.deleteAttribute(existing.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "id");
        verify(repository, never()).delete(any(CatalogAttributeDefinition.class));
    }

    @Test
    void bulkDeleteSkipsTheAttributesALiveVariantStillCarries() {
        // The same round: the bulk leg's honest-skip form of the same
        // refusal — the count answers only what the registry itself
        // deleted.
        CatalogAttributeDefinition free = row("free-attr", "Free");
        CatalogAttributeDefinition held = row("held-attr", "Held");
        when(repository.findByIdForUpdate(free.getId())).thenReturn(Optional.of(free));
        when(repository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));
        // The probe discriminates on the ASSIGNMENT ids the attribute's
        // children carry: the held attribute has a live assignment, the
        // free one has none.
        ProductTypeAttributeAssignment heldAssignment = ProductTypeAttributeAssignment.create(
                UUID.randomUUID(), held.getId(), ProductTypeAttributeScope.VARIANT_SELECTION, true, 1);
        when(assignmentRepository.findAllByAttributeDefinitionId(held.getId()))
                .thenReturn(List.of(heldAssignment));
        when(assignmentRepository.findAllByAttributeDefinitionId(free.getId()))
                .thenReturn(List.of());
        org.mockito.Mockito.when(variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(invocation -> !((java.util.Collection<?>) invocation.getArgument(0)).isEmpty());

        int count = service.deleteAttributes(java.util.Arrays.asList(free.getId(), held.getId()));

        assertThat(count).isEqualTo(1);
        verify(repository).delete(free);
        verify(repository, never()).delete(held);
    }

    /**
     * The 2026-11-07 #808 round-4 review (Minor · Stability &amp;
     * Availability): the bulk attribute delete takes its definition locks
     * in SORTED id order — two concurrent bulk deletes naming two
     * definitions in OPPOSITE orders can never deadlock (each would hold
     * one row while waiting for the other under the caller-order shape).
     */
    @Test
    void bulkDeleteLocksTheDefinitionsInSortedOrder() {
        CatalogAttributeDefinition first = row("first-attr", "First");
        CatalogAttributeDefinition second = row("second-attr", "Second");
        when(repository.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(repository.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));

        // The caller names the two attributes in the OPPOSITE of the sorted
        // order — the locks still come sorted.
        service.deleteAttributes(java.util.Arrays.asList(second.getId(), first.getId()));

        java.util.List<UUID> sorted = java.util.stream.Stream.of(first.getId(), second.getId())
                .sorted().toList();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).findByIdForUpdate(sorted.get(0));
        order.verify(repository).findByIdForUpdate(sorted.get(1));
        verify(repository).delete(first);
        verify(repository).delete(second);
    }

    // ------------------------------------------------------------------
    // deleteAttribute / deleteAttributes
    // ------------------------------------------------------------------

    @Test
    void deleteOfAnUnknownRowAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        AttributeMutationResult result = service.deleteAttribute(unknown);

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
        verify(repository, never()).delete(any(CatalogAttributeDefinition.class));
    }

    @Test
    void deleteAnswersTheLastKnownProjection() {
        CatalogAttributeDefinition existing = row("gone-attr", "Gone");
        when(repository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));

        AttributeMutationResult result = service.deleteAttribute(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().slug()).isEqualTo("gone-attr");
        assertThat(result.attribute().name()).isEqualTo("Gone");
        verify(repository).delete(existing);
    }

    @Test
    void deleteCascadesTheLiveOptionsAndAssignmentsWithTheAttribute() {
        // The wave-19 #808 review (Major · Data Integrity): the soft
        // delete's children go WITH the attribute — a live option or
        // assignment row left behind keeps referencing a deleted parent
        // (the FK never fires on a soft delete) and a later option
        // translation hits the parent-absent throw.
        CatalogAttributeDefinition existing = row("gone-attr", "Gone");
        CatalogAttributeOption option = CatalogAttributeOption.create(
                existing.getId(), "gone-opt", "Gone Opt", null, 1);
        ProductTypeAttributeAssignment assignment = ProductTypeAttributeAssignment.create(
                UUID.randomUUID(), existing.getId(), ProductTypeAttributeScope.PRODUCT, false, 1);
        when(repository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(existing.getId()))
                .thenReturn(List.of(option));
        when(assignmentRepository.findAllByAttributeDefinitionId(existing.getId()))
                .thenReturn(List.of(assignment));

        AttributeMutationResult result = service.deleteAttribute(existing.getId());

        assertThat(result.success()).isTrue();
        verify(optionRepository).deleteAll(List.of(option));
        verify(assignmentRepository).deleteAll(List.of(assignment));
        verify(repository).delete(existing);
    }

    @Test
    void bulkDeleteCascadesTheChildrenPerDeletedRow() {
        CatalogAttributeDefinition first = row("first-attr", "First");
        CatalogAttributeOption firstOption = CatalogAttributeOption.create(
                first.getId(), "first-opt", "First Opt", null, 1);
        when(repository.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(first.getId()))
                .thenReturn(List.of(firstOption));
        when(assignmentRepository.findAllByAttributeDefinitionId(first.getId()))
                .thenReturn(List.of());

        int count = service.deleteAttributes(java.util.Arrays.asList(first.getId(), UUID.randomUUID()));

        assertThat(count).isEqualTo(1);
        verify(optionRepository).deleteAll(List.of(firstOption));
        verify(repository).delete(first);
    }

    @Test
    void bulkDeleteAnswersTheHonestCount() {
        CatalogAttributeDefinition first = row("first-attr", "First");
        CatalogAttributeDefinition second = row("second-attr", "Second");
        when(repository.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(repository.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));

        // Arrays.asList carries the null member the honest-skip case
        // feeds the owner (List.of's own null refusal is not the seam).
        int count = service.deleteAttributes(java.util.Arrays.asList(
                first.getId(), second.getId(), UUID.randomUUID(), null));

        assertThat(count).isEqualTo(2);
        verify(repository).delete(first);
        verify(repository).delete(second);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZero() {
        assertThat(service.deleteAttributes(List.of())).isZero();
        assertThat(service.deleteAttributes(null)).isZero();
        verify(repository, never()).delete(any(CatalogAttributeDefinition.class));
    }

    // ------------------------------------------------------------------
    // the rendered projection
    // ------------------------------------------------------------------

    @Test
    void theProjectionRendersTheBilingualFallbackName() {
        // A nameAr-only row renders nameAr (the read service's own
        // fallback rule); a nameEn row renders nameEn.
        CatalogAttributeDefinition arOnly = CatalogAttributeDefinition.create(
                "ar-attr", null, "الاسم العربي فقط", CatalogAttributeType.TEXT, null);
        when(repository.findByIdForUpdate(arOnly.getId())).thenReturn(Optional.of(arOnly));

        AttributeMutationResult result = service.deleteAttribute(arOnly.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().name()).isEqualTo("الاسم العربي فقط");
        assertThat(result.attribute().slug()).isEqualTo("ar-attr");
    }

    // ------------------------------------------------------------------
    // reorderValues — the Saleor attributeReorderValues root (the
    // agent-2 follow-on packet)
    // ------------------------------------------------------------------

    @Test
    void reorderValuesAnswersTheNotFoundForAnUnknownAttribute() {
        AttributeMutationResult result = service.reorderValues(UUID.randomUUID(),
                List.of(new AttributeReorderMove(UUID.randomUUID(), 1)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "attributeId");
    }

    @Test
    void reorderValuesResolvesTheRelativeMovesWithCompactedPositions() {
        CatalogAttributeDefinition parent = row("choice-attr", "Choice Attr");
        CatalogAttributeOption one = CatalogAttributeOption.create(parent.getId(), "one", "One", null, 1);
        CatalogAttributeOption two = CatalogAttributeOption.create(parent.getId(), "two", "Two", null, 2);
        CatalogAttributeOption three = CatalogAttributeOption.create(parent.getId(), "three", "Three", null, 3);
        when(repository.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(parent.getId()))
                .thenReturn(List.of(one, two, three));
        // The stub snapshots each write's positions AT INVOCATION TIME: both
        // saveAll calls carry the SAME mutable list, so an argThat-only proof
        // would see the phase-2 finals on both (the parked write's absence
        // would pass unnoticed). The captured sequence is the real two-phase
        // evidence — park (4, 5, 6), then the dense finals (1, 2, 3).
        List<List<Integer>> savedPositions = new ArrayList<>();
        when(optionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CatalogAttributeOption> saved = invocation.getArgument(0);
            savedPositions.add(saved.stream()
                    .map(CatalogAttributeOption::getPosition)
                    .toList());
            return saved;
        });

        AttributeMutationResult result = service.reorderValues(parent.getId(),
                List.of(new AttributeReorderMove(three.getId(), -2)));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().slug()).isEqualTo("choice-attr");
        // The two-phase rewrite the live-position unique index demands:
        // the park write lands first (the whole bucket parked beyond the
        // current max — 4, 5, 6), the flush separates the phases, and the
        // final dense order lands second (1, 2, 3) — both writes carry the
        // same list reference, so the phase-2 finals are what the argThat
        // matcher sees on BOTH invocations; the snapshot sequence and the
        // in-order verification are the proof the parked write happened.
        verify(optionRepository, times(2)).saveAll(argThat((List<CatalogAttributeOption> saved) ->
                saved.size() == 3
                        && saved.get(0).getId().equals(three.getId()) && saved.get(0).getPosition() == 1
                        && saved.get(1).getId().equals(one.getId()) && saved.get(1).getPosition() == 2
                        && saved.get(2).getId().equals(two.getId()) && saved.get(2).getPosition() == 3));
        assertThat(savedPositions).containsExactly(List.of(4, 5, 6), List.of(1, 2, 3));
        InOrder writes = inOrder(optionRepository);
        writes.verify(optionRepository).saveAll(any());
        writes.verify(optionRepository).flush();
        writes.verify(optionRepository).saveAll(any());
    }

    @Test
    void reorderValuesAnswersTheNotFoundForAnUnknownValue() {
        CatalogAttributeDefinition parent = row("choice-attr", "Choice Attr");
        when(repository.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(parent.getId()))
                .thenReturn(List.of());

        AttributeMutationResult result = service.reorderValues(parent.getId(),
                List.of(new AttributeReorderMove(UUID.randomUUID(), 1)));

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void reorderValuesAnswersTheInvalidForAnOutOfRangeMove() {
        CatalogAttributeDefinition parent = row("choice-attr", "Choice Attr");
        CatalogAttributeOption one = CatalogAttributeOption.create(parent.getId(), "one", "One", null, 1);
        when(repository.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(parent.getId()))
                .thenReturn(List.of(one));

        AttributeMutationResult result = service.reorderValues(parent.getId(),
                List.of(new AttributeReorderMove(one.getId(), 1)));

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "sortOrder");
        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void reorderValuesWithAZeroShiftIsTheIdempotentNoOp() {
        CatalogAttributeDefinition parent = row("choice-attr", "Choice Attr");
        CatalogAttributeOption one = CatalogAttributeOption.create(parent.getId(), "one", "One", null, 1);
        when(repository.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(parent.getId()))
                .thenReturn(List.of(one));

        AttributeMutationResult result = service.reorderValues(parent.getId(),
                List.of(new AttributeReorderMove(one.getId(), 0)));

        assertThat(result.success()).isTrue();
        verify(optionRepository, never()).saveAll(any());
    }
    // ------------------------------------------------------------------
    // the bulk roots — the pinned policy's own semantics (the agent-2
    // follow-on packet)
    // ------------------------------------------------------------------

    @Test
    void bulkCreateReturnsThePerItemOutcomes() {
        when(repository.findByCode("bulk-one")).thenReturn(Optional.empty());
        when(repository.findByCode("bulk-two")).thenReturn(Optional.empty());
        when(repository.findByCode("taken")).thenReturn(Optional.of(row("taken", "Taken")));
        when(repository.save(any(CatalogAttributeDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var outcomes = service.bulkCreateAttributes(List.of(
                new AttributeCreateCommand("Bulk One", "bulk-one", CatalogAttributeType.TEXT),
                new AttributeCreateCommand("Bulk Two", "bulk-two", CatalogAttributeType.TEXT),
                new AttributeCreateCommand("Taken Name", "taken", CatalogAttributeType.TEXT)),
                "IGNORE_FAILED");

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).success()).isTrue();
        assertThat(outcomes.get(1).success()).isTrue();
        assertThat(outcomes.get(2).success()).isFalse();
        assertThat(outcomes.get(2).errors()).singleElement()
                .extracting(AttributeMutationError::code)
                .isEqualTo(AttributeMutationErrorCode.ALREADY_EXISTS);
    }

    @Test
    void bulkUpdateReturnsThePerItemOutcomes() {
        CatalogAttributeDefinition row = row("bulk-upd", "Bulk Upd");
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        when(repository.save(any(CatalogAttributeDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var outcomes = service.bulkUpdateAttributes(List.of(
                new AttributeBulkUpdateItem(row.getId(), new AttributeUpdateCommand("Renamed", null)),
                new AttributeBulkUpdateItem(UUID.randomUUID(), new AttributeUpdateCommand("Ghost", null))),
                "IGNORE_FAILED");

        assertThat(outcomes.get(0).success()).isTrue();
        assertThat(outcomes.get(0).attribute().name()).isEqualTo("Renamed");
        assertThat(outcomes.get(1).success()).isFalse();
        assertThat(outcomes.get(1).errors()).singleElement()
                .extracting(AttributeMutationError::code)
                .isEqualTo(AttributeMutationErrorCode.NOT_FOUND);
    }

    @Test
    void unknownErrorPolicyRefusesEveryItemWithoutAnyWrite() {
        // The wave-19 #808 review (Minor): an unknown (misspelt or
        // lower-cased) policy name is the classified INVALID refusal for
        // every item — it never falls through to the non-atomic
        // persistence path the old atomic() predicate implied.
        var outcomes = service.bulkCreateAttributes(List.of(
                new AttributeCreateCommand("Bulk One", "bulk-one", CatalogAttributeType.TEXT)),
                "reject_everything");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).attribute()).isNull();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "errorPolicy");
        verify(repository, never()).save(any(CatalogAttributeDefinition.class));
    }

    @Test
    void bulkUpdateWithAnUnknownPolicyRefusesEveryItemWithoutAnyWrite() {
        var outcomes = service.bulkUpdateAttributes(List.of(
                new AttributeBulkUpdateItem(UUID.randomUUID(), new AttributeUpdateCommand("Ghost", null))),
                "REJECT_EVRYTHING");

        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "errorPolicy");
        verify(repository, never()).save(any(CatalogAttributeDefinition.class));
    }

    @Test
    void rejectEverythingRollbackRewritesTheSuccessfulOutcomesWithoutAProjection() {
        // The wave-19 #808 review (Major): when the REJECT_EVERYTHING
        // policy rolls the batch back, the passing items' outcomes lose
        // their phantom projections — a success outcome would hand the
        // client ids that were never persisted.
        when(repository.findByCode("bulk-ok")).thenReturn(Optional.empty());
        when(repository.findByCode("bulk-bad")).thenReturn(Optional.of(row("bulk-bad", "Bad")));
        when(repository.save(any(CatalogAttributeDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        try (MockedStatic<TransactionAspectSupport> transaction =
                     org.mockito.Mockito.mockStatic(TransactionAspectSupport.class)) {
            transaction.when(TransactionAspectSupport::currentTransactionStatus)
                    .thenReturn(mock(TransactionStatus.class));

            var outcomes = service.bulkCreateAttributes(List.of(
                    new AttributeCreateCommand("Bulk Ok", "bulk-ok", CatalogAttributeType.TEXT),
                    new AttributeCreateCommand("Taken", "bulk-bad", CatalogAttributeType.TEXT)),
                    "REJECT_EVERYTHING");

            assertThat(outcomes).hasSize(2);
            // NO item carries a success outcome: the rollback erased the
            // saved row, so the passing item answers the batch's own
            // classified refusal with no projection at all.
            assertThat(outcomes.get(0).success()).isFalse();
            assertThat(outcomes.get(0).attribute()).isNull();
            assertThat(outcomes.get(0).errors()).singleElement()
                    .extracting(AttributeMutationError::code, AttributeMutationError::field)
                    .containsExactly(AttributeMutationErrorCode.INVALID, "errorPolicy");
            assertThat(outcomes.get(1).success()).isFalse();
            assertThat(outcomes.get(1).errors()).singleElement()
                    .extracting(AttributeMutationError::code)
                    .isEqualTo(AttributeMutationErrorCode.ALREADY_EXISTS);
        }
    }

    /**
     * The 2026-11-06 #808 round review (Minor · Stability &amp;
     * Availability): a bucket holding the LEGAL positions 0 and 1 (both
     * reachable through registerOption's first position and the DB's
     * position-check) parks at max(maxPosition, size) + 1 = 3 — under
     * the max-only rule the park range [2, 3] overlapped the phase-2
     * dense range [1, 2] and a phase-2 write could land on a
     * not-yet-vacated seat (the flush order is not guaranteed). The
     * captured two-phase sequence proves the disjoint ranges.
     */
    @Test
    void reorderValuesParksAZeroBasedBucketBeyondTheDenseRange() {
        CatalogAttributeDefinition parent = row("zero-attr", "Zero Attr");
        CatalogAttributeOption zero = CatalogAttributeOption.create(parent.getId(), "zero", "Zero", null, 0);
        CatalogAttributeOption one = CatalogAttributeOption.create(parent.getId(), "one", "One", null, 1);
        when(repository.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(parent.getId()))
                .thenReturn(List.of(zero, one));
        List<List<Integer>> savedPositions = new ArrayList<>();
        when(optionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CatalogAttributeOption> saved = invocation.getArgument(0);
            savedPositions.add(saved.stream()
                    .map(CatalogAttributeOption::getPosition).toList());
            return saved;
        });

        AttributeMutationResult result = service.reorderValues(parent.getId(),
                List.of(new AttributeReorderMove(one.getId(), -1)));

        assertThat(result.success()).isTrue();
        // Phase 1 parks at [3, 4] — strictly above BOTH the stored max (1)
        // and the dense target range [1, 2] — then phase 2 writes [2, 1]
        // (the one moved up, zero shifted down).
        assertThat(savedPositions).hasSize(2);
        assertThat(savedPositions.get(0)).containsExactly(3, 4);
        assertThat(savedPositions.get(1)).containsExactly(1, 2);
    }
}
