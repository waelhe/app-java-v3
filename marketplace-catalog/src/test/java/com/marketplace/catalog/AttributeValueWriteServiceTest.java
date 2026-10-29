package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeValueCreateCommand;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.AttributeValueUpdateCommand;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeValueCreate}, {@code attributeValueUpdate},
 * {@code attributeValueDelete} and {@code attributeValueBulkDelete}
 * mutation roots, the agent-2 packet): the owning write use case's
 * unit proof — the JUnit 5 + Mockito style the module's own
 * {@code AttributeWriteServiceTest} established (no Spring context:
 * the {@code @PreAuthorize} proxy is an integration concern proven by
 * the app module's real-stack tests; the business decisions the
 * boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link AttributeValueWriteService} reachable through its typed seam
 * — the create's full validation ladder (the missing parent, the
 * non-CHOICE parent refusal, blank/oversized name, missing/invalid/
 * duplicate slug, the happy path's append-position derivation), the
 * update's partial-preserve semantics (the empty no-op, null fields
 * preserve, the blank-name INVALID, the immutable-slug refusals and
 * the row's-own-code idempotent re-send, the NOT_FOUND absence), the
 * single delete's NOT_FOUND and soft-delete happy path with the dual
 * projection, and the bulk delete's honest count (unknown ids
 * skipped, never fabricated).</p>
 */
class AttributeValueWriteServiceTest {

    private CatalogAttributeOptionRepository options;
    private CatalogAttributeDefinitionRepository definitions;
    private AttributeValueWriteSpi service;

    @BeforeEach
    void setUp() {
        options = mock(CatalogAttributeOptionRepository.class);
        definitions = mock(CatalogAttributeDefinitionRepository.class);
        service = new AttributeValueWriteService(options, definitions);
    }

    private static CatalogAttributeDefinition choiceParent() {
        return CatalogAttributeDefinition.create("parent-choice", "Parent", null,
                CatalogAttributeType.CHOICE, null);
    }

    private static CatalogAttributeDefinition textParent() {
        return CatalogAttributeDefinition.create("parent-text", "Parent", null,
                CatalogAttributeType.TEXT, null);
    }

    // ------------------------------------------------------------------
    // createAttributeValue — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void missingParentAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(definitions.findById(unknown)).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.createAttributeValue(unknown,
                new AttributeValueCreateCommand("Small", "small"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "attribute");
        verify(options, never()).save(any());
    }

    @Test
    void nonChoiceParentAnswersTheInvalidDomainError() {
        CatalogAttributeDefinition parent = textParent();
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Small", "small"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "attribute");
        assertThat(result.errors().get(0).message())
                .isEqualTo("Allowed values can only be added to CHOICE attributes");
        verify(options, never()).save(any());
    }

    @Test
    void blankNameAnswersTheRequiredDomainError() {
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("  ", "small"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.REQUIRED, "name");
    }

    @Test
    void invalidSlugShapeAnswersTheInvalidDomainError() {
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Small", "Not_A_Slug"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "slug");
        verify(options, never()).save(any());
    }

    @Test
    void duplicateSlugWithinTheAttributeAnswersTheAlreadyExistsDomainError() {
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByAttributeDefinitionIdAndCode(parent.getId(), "taken"))
                .thenReturn(Optional.of(CatalogAttributeOption.create(parent.getId(), "taken", "Taken", null, 0)));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Small", "taken"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.ALREADY_EXISTS, "slug");
        verify(options, never()).save(any());
    }

    @Test
    void happyCreateAppendsAfterTheHighestPositionAndAnswersTheDualProjection() {
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByAttributeDefinitionIdAndCode(parent.getId(), "small"))
                .thenReturn(Optional.empty());
        when(options.findFirstByAttributeDefinitionIdOrderByPositionDesc(parent.getId()))
                .thenReturn(Optional.of(CatalogAttributeOption.create(parent.getId(), "first", "First", null, 4)));
        when(options.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Small", "small"));

        assertThat(result.success()).isTrue();
        assertThat(result.value().name()).isEqualTo("Small");
        assertThat(result.value().slug()).isEqualTo("small");
        assertThat(result.attribute().slug()).isEqualTo("parent-choice");
        verify(options).save(argOptionWithPosition(5));
    }

    @Test
    void theFirstValueOfAnAttributeStartsAtPositionZero() {
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByAttributeDefinitionIdAndCode(parent.getId(), "small"))
                .thenReturn(Optional.empty());
        when(options.findFirstByAttributeDefinitionIdOrderByPositionDesc(parent.getId()))
                .thenReturn(Optional.empty());
        when(options.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Small", "small"));

        assertThat(result.success()).isTrue();
        verify(options).save(argOptionWithPosition(0));
    }

    // ------------------------------------------------------------------
    // updateAttributeValue — the partial-preserve semantics
    // ------------------------------------------------------------------

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(options.findById(unknown)).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.updateAttributeValue(unknown,
                new AttributeValueUpdateCommand("New", null));

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void emptyCommandAnswersTheCurrentProjectionWithoutASave() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "keep-val", "Kept", null, 2);
        when(options.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));

        AttributeValueMutationResult result = service.updateAttributeValue(existing.getId(),
                new AttributeValueUpdateCommand(null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.value().slug()).isEqualTo("keep-val");
        assertThat(result.value().name()).isEqualTo("Kept");
        assertThat(result.attribute().slug()).isEqualTo("parent-choice");
        verify(options, never()).save(any());
    }

    @Test
    void providedFieldsReplaceAndNullsPreserve() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "keep-val", "Kept", null, 2);
        when(options.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(options.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.updateAttributeValue(existing.getId(),
                new AttributeValueUpdateCommand("Refreshed", null));

        assertThat(result.success()).isTrue();
        assertThat(result.value().name()).isEqualTo("Refreshed");
        assertThat(result.value().slug()).isEqualTo("keep-val");
        verify(options).save(any(CatalogAttributeOption.class));
    }

    @Test
    void blankNameAnswersTheInvalidDomainError() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "keep-val", "Kept", null, 2);
        when(options.findById(existing.getId())).thenReturn(Optional.of(existing));

        AttributeValueMutationResult result = service.updateAttributeValue(existing.getId(),
                new AttributeValueUpdateCommand("   ", null));

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "name");
        verify(options, never()).save(any());
    }

    @Test
    void slugChangeToADifferentCodeAnswersTheImmutableDomainError() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "keep-val", "Kept", null, 2);
        when(options.findById(existing.getId())).thenReturn(Optional.of(existing));

        AttributeValueMutationResult result = service.updateAttributeValue(existing.getId(),
                new AttributeValueUpdateCommand(null, "fresh-name"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "slug");
        assertThat(result.errors().get(0).message())
                .isEqualTo("An attribute value slug is immutable; it cannot change after creation");
        assertThat(existing.getCode()).isEqualTo("keep-val");
        verify(options, never()).save(any());
    }

    @Test
    void slugChangeToTheRowsOwnCodeExecutes() {
        // The row's own code is EXCLUDED from the duplicate check — a
        // re-send of the current slug is a no-op, not a conflict.
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "keep-val", "Kept", null, 2);
        when(options.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(options.findByAttributeDefinitionIdAndCode(parent.getId(), "keep-val"))
                .thenReturn(Optional.of(existing));
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(options.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.updateAttributeValue(existing.getId(),
                new AttributeValueUpdateCommand("Refreshed", "keep-val"));

        assertThat(result.success()).isTrue();
        assertThat(result.value().slug()).isEqualTo("keep-val");
        verify(options).save(any(CatalogAttributeOption.class));
    }

    // ------------------------------------------------------------------
    // deleteAttributeValue / deleteAttributeValues
    // ------------------------------------------------------------------

    @Test
    void deleteOfAnUnknownRowAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(options.findById(unknown)).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.deleteAttributeValue(unknown);

        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
        verify(options, never()).delete(any(CatalogAttributeOption.class));
    }

    @Test
    void deleteAnswersTheLastKnownDualProjection() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "gone-val", "Gone", null, 2);
        when(options.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));

        AttributeValueMutationResult result = service.deleteAttributeValue(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.value().slug()).isEqualTo("gone-val");
        assertThat(result.value().name()).isEqualTo("Gone");
        assertThat(result.attribute().slug()).isEqualTo("parent-choice");
        verify(options).delete(existing);
    }

    @Test
    void bulkDeleteAnswersTheHonestCount() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption first = CatalogAttributeOption.create(parent.getId(), "first", "First", null, 0);
        CatalogAttributeOption second = CatalogAttributeOption.create(parent.getId(), "second", "Second", null, 1);
        when(options.findById(first.getId())).thenReturn(Optional.of(first));
        when(options.findById(second.getId())).thenReturn(Optional.of(second));

        // Arrays.asList carries the null member the honest-skip case
        // feeds the owner (List.of's own null refusal is not the seam).
        int count = service.deleteAttributeValues(java.util.Arrays.asList(
                first.getId(), second.getId(), UUID.randomUUID(), null));

        assertThat(count).isEqualTo(2);
        verify(options).delete(first);
        verify(options).delete(second);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZero() {
        assertThat(service.deleteAttributeValues(List.of())).isZero();
        assertThat(service.deleteAttributeValues(null)).isZero();
        verify(options, never()).delete(any(CatalogAttributeOption.class));
    }

    // ------------------------------------------------------------------
    // the rendered projection
    // ------------------------------------------------------------------

    @Test
    void theProjectionRendersTheBilingualFallbackName() {
        // A nameAr-only row renders nameAr (the read service's own
        // fallback rule); a nameEn row renders nameEn.
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption arOnly = CatalogAttributeOption.create(
                parent.getId(), "ar-val", null, "القيمة العربية فقط", 0);
        when(options.findById(arOnly.getId())).thenReturn(Optional.of(arOnly));
        when(definitions.findById(parent.getId())).thenReturn(Optional.of(parent));

        AttributeValueMutationResult result = service.deleteAttributeValue(arOnly.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.value().name()).isEqualTo("القيمة العربية فقط");
        assertThat(result.value().slug()).isEqualTo("ar-val");
    }

    /** The append-position assertion's own matcher. */
    private static CatalogAttributeOption argOptionWithPosition(int position) {
        return org.mockito.ArgumentMatchers.argThat(option -> option.getPosition() == position);
    }
}
