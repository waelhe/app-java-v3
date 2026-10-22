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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    private AttributeWriteSpi service;

    @BeforeEach
    void setUp() {
        repository = mock(CatalogAttributeDefinitionRepository.class);
        optionRepository = mock(CatalogAttributeOptionRepository.class);
        service = new AttributeWriteService(repository, optionRepository);
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

    // ------------------------------------------------------------------
    // deleteAttribute / deleteAttributes
    // ------------------------------------------------------------------

    @Test
    void deleteOfAnUnknownRowAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        AttributeMutationResult result = service.deleteAttribute(unknown);

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
        verify(repository, never()).delete(any(CatalogAttributeDefinition.class));
    }

    @Test
    void deleteAnswersTheLastKnownProjection() {
        CatalogAttributeDefinition existing = row("gone-attr", "Gone");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        AttributeMutationResult result = service.deleteAttribute(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().slug()).isEqualTo("gone-attr");
        assertThat(result.attribute().name()).isEqualTo("Gone");
        verify(repository).delete(existing);
    }

    @Test
    void bulkDeleteAnswersTheHonestCount() {
        CatalogAttributeDefinition first = row("first-attr", "First");
        CatalogAttributeDefinition second = row("second-attr", "Second");
        when(repository.findById(first.getId())).thenReturn(Optional.of(first));
        when(repository.findById(second.getId())).thenReturn(Optional.of(second));

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
        when(repository.findById(arOnly.getId())).thenReturn(Optional.of(arOnly));

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
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(optionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(parent.getId()))
                .thenReturn(List.of(one, two, three));
        when(optionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeMutationResult result = service.reorderValues(parent.getId(),
                List.of(new AttributeReorderMove(three.getId(), -2)));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().slug()).isEqualTo("choice-attr");
        // The park-then-settle rewrite: the first saveAll parks every moved
        // row on a DISTINCT position ABOVE the live 1..n range (the
        // entity's reposition law rejects negatives), the second settles
        // the final compacted 1..n order.
        org.mockito.ArgumentCaptor<List<CatalogAttributeOption>> reorderCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(optionRepository, times(2)).saveAll(reorderCaptor.capture());
        List<CatalogAttributeOption> parked = reorderCaptor.getAllValues().get(0);
        assertThat(parked).extracting(CatalogAttributeOption::getPosition)
                .allSatisfy(position -> assertThat(position).isGreaterThan(3));
        assertThat(parked.stream().map(CatalogAttributeOption::getPosition).distinct().count())
                .isEqualTo(parked.size());
        List<CatalogAttributeOption> settled = reorderCaptor.getAllValues().get(1);
        assertThat(settled).hasSize(3);
        assertThat(settled.get(0).getId()).isEqualTo(three.getId());
        assertThat(settled.get(0).getPosition()).isEqualTo(1);
        assertThat(settled.get(1).getId()).isEqualTo(one.getId());
        assertThat(settled.get(1).getPosition()).isEqualTo(2);
        assertThat(settled.get(2).getId()).isEqualTo(two.getId());
        assertThat(settled.get(2).getPosition()).isEqualTo(3);
    }

    @Test
    void reorderValuesAnswersTheNotFoundForAnUnknownValue() {
        CatalogAttributeDefinition parent = row("choice-attr", "Choice Attr");
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));
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
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));
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
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));
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
}
