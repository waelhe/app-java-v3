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
import static org.mockito.Mockito.lenient;
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
    private ProductVariantAttributeValueRepository variantValues;
    private ProductAttributeValueRepository productValues;
    private AttributeValueWriteSpi service;

    @BeforeEach
    void setUp() {
        options = mock(CatalogAttributeOptionRepository.class);
        definitions = mock(CatalogAttributeDefinitionRepository.class);
        variantValues = mock(ProductVariantAttributeValueRepository.class);
        productValues = mock(ProductAttributeValueRepository.class);
        // The 2026-11-06 #808 round: the option-delete guard's live-reference
        // probe answers NO references by default (each test overrides it).
        lenient().when(variantValues.existsByOptionId(any())).thenReturn(false);
        // The 2026-11-06 #808 round-3: the PRODUCT-scope twin answers NO
        // references by default (each test overrides it).
        lenient().when(productValues.existsLiveProductValueForOption(any())).thenReturn(false);
        // The 2026-11-09 #808 round-26 review: the service rides the
        // repository's OWN locked load (findByIdForUpdate) — no
        // EntityManager seam remains, and the drop/lock/load order's
        // scaffolding stubs its own reads per test.
        service = new AttributeValueWriteService(options, definitions, variantValues, productValues);
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
        when(definitions.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

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
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));

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
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));

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
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));

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
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
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
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
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
    void theFirstValueOfAnAttributeStartsAtPositionOne() {
        // The 2026-11-05 #808 round (Minor · Functional Correctness):
        // the first position is 1 — aligned with the reorder's 1-based
        // no-change probe and compaction (i + 1). The previous 0-based
        // start made every fresh single-option attribute look "changed"
        // to the probe and forced a renumbering write on a true no-op.
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByAttributeDefinitionIdAndCode(parent.getId(), "small"))
                .thenReturn(Optional.empty());
        when(options.findFirstByAttributeDefinitionIdOrderByPositionDesc(parent.getId()))
                .thenReturn(Optional.empty());
        when(options.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Small", "small"));

        assertThat(result.success()).isTrue();
        verify(options).save(argOptionWithPosition(1));
    }

    // ------------------------------------------------------------------
    // updateAttributeValue — the partial-preserve semantics
    // ------------------------------------------------------------------

    /**
     * The 2026-11-09 #808 round-26 review: the drop/lock/load scaffolding —
     * the DEFINITION-ID projection answers the owning family for the id
     * (empty for an unknown option), the family lock is the definitions'
     * own FOR-UPDATE read, and the option's locked load answers the
     * authoritative row.
     */
    private void stubOwnedOption(CatalogAttributeOption option, CatalogAttributeDefinition parent) {
        when(options.findDefinitionIdById(option.getId()))
                .thenReturn(Optional.of(option.getAttributeDefinitionId()));
        when(definitions.findByIdForUpdate(option.getAttributeDefinitionId()))
                .thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(option.getId())).thenReturn(Optional.of(option));
    }

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(options.findDefinitionIdById(unknown)).thenReturn(Optional.empty());

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
        stubOwnedOption(existing, parent);

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
        stubOwnedOption(existing, parent);
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
        stubOwnedOption(existing, parent);
        // The 2026-11-06 #808 round (#11): the update path now answers the
        // classified NOT_FOUND when the parent attribute is gone — these
        // validation verdicts need the parent PRESENT to reach their own
        // error branch.

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
        stubOwnedOption(existing, parent);
        // The 2026-11-06 #808 round (#11): the update path now answers the
        // classified NOT_FOUND when the parent attribute is gone — these
        // validation verdicts need the parent PRESENT to reach their own
        // error branch.

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
        stubOwnedOption(existing, parent);
        when(options.findByAttributeDefinitionIdAndCode(parent.getId(), "keep-val")
                ).thenReturn(Optional.of(existing));
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
        when(options.findDefinitionIdById(unknown)).thenReturn(Optional.empty());

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
        stubOwnedOption(existing, parent);

        AttributeValueMutationResult result = service.deleteAttributeValue(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.value().slug()).isEqualTo("gone-val");
        assertThat(result.value().name()).isEqualTo("Gone");
        assertThat(result.attribute().slug()).isEqualTo("parent-choice");
        verify(options).delete(existing);
    }

    @Test
    void deleteAnswersThePostRefreshLastKnownProjection() {
        // The 2026-11-08 #808 round-22 review (Minor · Data Integrity &
        // Integration): the last-known projection is built AFTER the
        // under-lock re-read — a translation that committed while this
        // call waited on the definition lock is the name the delete
        // ACTUALLY removes, and the success payload answers THAT name
        // (never the stale pre-lock snapshot). The 2026-11-09 #808
        // round-26 review moved the re-read to the option's OWN locked
        // load: the load's answer IS the authoritative row, and the stub
        // here plays the reload — it mutates the row the way the
        // post-lock re-read would, then hands it back.
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "gone-val", "Gone", null, 2);
        when(options.findDefinitionIdById(existing.getId()))
                .thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(existing.getId())).thenAnswer(invocation -> {
            existing.revise("Replaced Under The Lock", existing.getNameAr());
            return Optional.of(existing);
        });

        AttributeValueMutationResult result = service.deleteAttributeValue(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.value().name()).isEqualTo("Replaced Under The Lock");
        verify(options).delete(existing);
    }

    @Test
    void bulkDeleteAnswersTheHonestCount() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption first = CatalogAttributeOption.create(parent.getId(), "first", "First", null, 0);
        CatalogAttributeOption second = CatalogAttributeOption.create(parent.getId(), "second", "Second", null, 1);
        when(options.findDefinitionIdById(first.getId())).thenReturn(Optional.of(parent.getId()));
        when(options.findDefinitionIdById(second.getId())).thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(options.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));

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
        stubOwnedOption(arOnly, parent);

        AttributeValueMutationResult result = service.deleteAttributeValue(arOnly.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.value().name()).isEqualTo("القيمة العربية فقط");
        assertThat(result.value().slug()).isEqualTo("ar-val");
    }

    /** The append-position assertion's own matcher. */
    private static CatalogAttributeOption argOptionWithPosition(int position) {
        return org.mockito.ArgumentMatchers.argThat(option -> option.getPosition() == position);
    }

    /**
     * The 2026-11-06 #808 round review (Major · Data Integrity &
     * Integration): an option referenced by a live variant answers the
     * classified INVALID refusal — the deleteProductType
     * live-references precedent (the references own their own
     * lifecycle, no cascade).
     */
    @Test
    void deleteAttributeValueRefusesAnOptionReferencedByALiveVariant() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "existing", "Existing", null, 1);
        stubOwnedOption(existing, parent);
        when(variantValues.existsByOptionId(existing.getId())).thenReturn(true);

        AttributeValueMutationResult result = service.deleteAttributeValue(existing.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(AttributeMutationErrorCode.INVALID);
        verify(options, never()).delete(any());
    }

    /**
     * The 2026-11-07 #808 round-4 review (Minor · Functional Correctness):
     * the ORPHANED option (its family died mid-race) still gets its cleanup
     * (the row goes with the call) but the payload answers the family's own
     * honest NOT_FOUND — never a partial success with a null attribute
     * projection (the result contract carries the parent on success).
     */
    @Test
    void deleteAttributeValueCleansUpAnOrphanedOptionWithTheHonestNotFound() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption orphaned = CatalogAttributeOption.create(
                parent.getId(), "orphaned", "Orphaned", null, 1);
        when(options.findDefinitionIdById(orphaned.getId()))
                .thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.empty());
        when(options.findByIdForUpdate(orphaned.getId())).thenReturn(Optional.of(orphaned));

        AttributeValueMutationResult result = service.deleteAttributeValue(orphaned.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "attribute");
        // The cleanup still happens — the row never outlives the call.
        verify(options).delete(orphaned);
    }

    @Test
    void anOrphanedOptionThatVanishedUnderItsOwnRefreshAnswersItsOwnNotFound() {
        // The 2026-11-08 #808 round-25 review, re-proven on the
        // 2026-11-09 round-26 shape: the dead family answers no definition
        // row, so NO family lock serializes the orphan cleanup against a
        // rival delete of the SAME option — the row can vanish before the
        // option's own locked load, and the load answers the option's own
        // honest absence as an EMPTY OPTIONAL: never an exception, never a
        // rollback-only poison riding the classified NOT_FOUND, and never
        // a cleanup delete riding a stale managed entity.
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption orphaned = CatalogAttributeOption.create(
                parent.getId(), "orphaned", "Orphaned", null, 1);
        when(options.findDefinitionIdById(orphaned.getId()))
                .thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.empty());
        when(options.findByIdForUpdate(orphaned.getId())).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.deleteAttributeValue(orphaned.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
        verify(options, org.mockito.Mockito.never()).delete(orphaned);
    }

    @Test
    void theBulkLegSkipsAnOptionThatVanishedBetweenItsProbeAndTheRefresh() {
        // The same round-25 finding's bulk half, re-proven on the
        // 2026-11-09 round-26 shape: the dead-family legs take NO family
        // lock, so a rival delete of the option can commit before the
        // option's own locked load — the load answers the EMPTY OPTIONAL
        // and the honest count SKIPS the vanished row. The round-26
        // defect this closes: the refresh-based skip used to poison the
        // session (rollback-only at the EntityNotFoundException
        // conversion), rolling the batch's EARLIER deletions back at
        // commit — the locked load keeps the survivor's deletion and
        // the honest count in the SAME transaction.
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption victim = CatalogAttributeOption.create(
                parent.getId(), "victim", "Victim", null, 1);
        CatalogAttributeOption survivor = CatalogAttributeOption.create(
                parent.getId(), "survivor", "Survivor", null, 2);
        when(options.findDefinitionIdById(victim.getId())).thenReturn(Optional.of(parent.getId()));
        when(options.findDefinitionIdById(survivor.getId())).thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.empty());
        when(options.findByIdForUpdate(victim.getId())).thenReturn(Optional.empty());
        when(options.findByIdForUpdate(survivor.getId())).thenReturn(Optional.of(survivor));

        int count = service.deleteAttributeValues(java.util.Arrays.asList(
                victim.getId(), survivor.getId()));

        // The vanished option is skipped (never an exception, never a
        // poisoned transaction), the survivor's cleanup still runs — the
        // honest count.
        assertThat(count).isEqualTo(1);
        verify(options, org.mockito.Mockito.never()).delete(victim);
        verify(options).delete(survivor);
    }

    /** The same round: the bulk leg skips the referenced options — the honest count. */
    @Test
    void deleteAttributeValuesSkipsReferencedOptions() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption free = CatalogAttributeOption.create(
                parent.getId(), "free", "Free", null, 1);
        CatalogAttributeOption held = CatalogAttributeOption.create(
                parent.getId(), "held", "Held", null, 2);
        when(options.findDefinitionIdById(free.getId())).thenReturn(Optional.of(parent.getId()));
        when(options.findDefinitionIdById(held.getId())).thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(free.getId())).thenReturn(Optional.of(free));
        when(options.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));
        when(variantValues.existsByOptionId(held.getId())).thenReturn(true);

        int count = service.deleteAttributeValues(java.util.Arrays.asList(free.getId(), held.getId()));

        assertThat(count).isEqualTo(1);
        verify(options).delete(free);
        verify(options, never()).delete(held);
        // The 2026-11-06 #808 round (Major · Data Integrity): the bulk
        // leg takes the SAME definition-row FOR-UPDATE lock the single
        // leg takes — the existsByOptionId probe's zero-references answer
        // is COMMITTED-stable against a concurrent registration's
        // FOR-SHARE read of the row.
        verify(definitions).findByIdForUpdate(parent.getId());
    }

    /**
     * The 2026-11-06 #808 round-3 review (Major · Data Integrity &amp;
     * Integration): the definition locks come BEFORE the reference probes —
     * the single-item leg's own lock-first order, which the previous
     * per-id bulk shape inverted (probe at the top, lock at the bottom —
     * a TOCTOU the concurrent registration's FOR-SHARE read walked
     * through).
     */
    @Test
    void bulkDeleteTakesTheDefinitionLocksBeforeTheReferenceProbes() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption first = CatalogAttributeOption.create(
                parent.getId(), "first", "First", null, 0);
        CatalogAttributeOption second = CatalogAttributeOption.create(
                parent.getId(), "second", "Second", null, 1);
        when(options.findDefinitionIdById(first.getId())).thenReturn(Optional.of(parent.getId()));
        when(options.findDefinitionIdById(second.getId())).thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(options.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));

        service.deleteAttributeValues(List.of(first.getId(), second.getId()));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(definitions, variantValues, productValues);
        // The shared lock pass precedes EVERY probe...
        order.verify(definitions).findByIdForUpdate(parent.getId());
        // ...and the per-option probe pairs answer only inside the locked
        // window (the delete loop's own interleave).
        order.verify(variantValues).existsByOptionId(first.getId());
        order.verify(productValues).existsLiveProductValueForOption(first.getId());
        order.verify(variantValues).existsByOptionId(second.getId());
        order.verify(productValues).existsLiveProductValueForOption(second.getId());
    }

    /**
     * The same round: two families lock in the SORTED definition-id order —
     * two concurrent bulk deletes naming overlapping families in different
     * orders can never deadlock.
     */
    @Test
    void bulkDeleteLocksDistinctFamiliesInSortedDefinitionOrder() {
        CatalogAttributeDefinition parentA = choiceParent();
        CatalogAttributeDefinition parentB = CatalogAttributeDefinition.create(
                "parent-b", "Parent B", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption underB = CatalogAttributeOption.create(
                parentB.getId(), "under-b", "Under B", null, 0);
        CatalogAttributeOption underA = CatalogAttributeOption.create(
                parentA.getId(), "under-a", "Under A", null, 0);
        // The caller names B's option FIRST — the lock order stays sorted.
        when(options.findDefinitionIdById(underB.getId())).thenReturn(Optional.of(parentB.getId()));
        when(options.findDefinitionIdById(underA.getId())).thenReturn(Optional.of(parentA.getId()));
        when(definitions.findByIdForUpdate(parentA.getId())).thenReturn(Optional.of(parentA));
        when(definitions.findByIdForUpdate(parentB.getId())).thenReturn(Optional.of(parentB));
        when(options.findByIdForUpdate(underB.getId())).thenReturn(Optional.of(underB));
        when(options.findByIdForUpdate(underA.getId())).thenReturn(Optional.of(underA));

        service.deleteAttributeValues(java.util.Arrays.asList(underB.getId(), underA.getId()));

        java.util.List<UUID> sorted = java.util.stream.Stream.of(parentA.getId(), parentB.getId())
                .sorted().toList();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(definitions);
        order.verify(definitions).findByIdForUpdate(sorted.get(0));
        order.verify(definitions).findByIdForUpdate(sorted.get(1));
    }

    /**
     * The same round: a live PRODUCT's own value referencing the option
     * answers the classified INVALID refusal — the PRODUCT-scope twin of
     * the live-variant probe (the publish path's re-validation would
     * strand the product on a dead schema).
     */
    @Test
    void deleteAttributeValueRefusesAnOptionReferencedByALiveProduct() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "existing", "Existing", null, 1);
        stubOwnedOption(existing, parent);
        when(productValues.existsLiveProductValueForOption(existing.getId())).thenReturn(true);

        AttributeValueMutationResult result = service.deleteAttributeValue(existing.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(AttributeMutationErrorCode.INVALID);
        verify(options, never()).delete(any());
    }

    /** The same round: the bulk leg's honest-skip form of the product-value refusal. */
    @Test
    void deleteAttributeValuesSkipsOptionsReferencedByLiveProducts() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption free = CatalogAttributeOption.create(
                parent.getId(), "free", "Free", null, 1);
        CatalogAttributeOption held = CatalogAttributeOption.create(
                parent.getId(), "held-product", "Held by product", null, 2);
        when(options.findDefinitionIdById(free.getId())).thenReturn(Optional.of(parent.getId()));
        when(options.findDefinitionIdById(held.getId())).thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(free.getId())).thenReturn(Optional.of(free));
        when(options.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));
        when(productValues.existsLiveProductValueForOption(held.getId())).thenReturn(true);

        int count = service.deleteAttributeValues(java.util.Arrays.asList(free.getId(), held.getId()));

        assertThat(count).isEqualTo(1);
        verify(options).delete(free);
        verify(options, never()).delete(held);
    }

    /** The same round: a concurrently-deleted parent answers the classified NOT_FOUND. */
    @Test
    void updateAttributeValueAnswersNotFoundWhenTheParentAttributeIsGone() {
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption existing = CatalogAttributeOption.create(
                parent.getId(), "existing", "Existing", null, 1);
        when(options.findDefinitionIdById(existing.getId()))
                .thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.updateAttributeValue(existing.getId(),
                new AttributeValueUpdateCommand("Renamed", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors().get(0).code()).isEqualTo(AttributeMutationErrorCode.NOT_FOUND);
    }

    @Test
    void appendAnswersTheClassifiedExhaustionWhenTheCeilingPositionIsStored() {
        // The 2026-11-07 #808 round-19 review (the overflow family): the
        // REST registerOption surface accepts a client-supplied position
        // with only a @Min(0) guard — a stored Integer.MAX_VALUE wraps
        // the max+1 derivation negative and the DB CHECK answered an
        // unclassified failure. The shared law answers the classified
        // INVALID error naming the compaction path (the reorder).
        CatalogAttributeDefinition parent = choiceParent();
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        CatalogAttributeOption ceiling = CatalogAttributeOption.create(parent.getId(),
                "ceiling", "Ceiling", null, Integer.MAX_VALUE);
        when(options.findFirstByAttributeDefinitionIdOrderByPositionDesc(parent.getId()))
                .thenReturn(Optional.of(ceiling));

        AttributeValueMutationResult result = service.createAttributeValue(parent.getId(),
                new AttributeValueCreateCommand("Fresh", "fresh"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "position");
        assertThat(result.errors().get(0).message()).contains("reorder");
        verify(options, never()).save(any());
    }

    @Test
    void deleteAnswersTheHonestNotFoundWhenTheOptionDiedUnderTheLock() {
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness), re-proven on the round-26 shape: the option's own
        // locked load answers the row's post-lock absence — a concurrent
        // delete of the SAME option that committed while this call waited
        // on the family lock answers the honest NOT_FOUND (never a
        // fabricated SUCCESS off a stale row).
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption victim = CatalogAttributeOption.create(parent.getId(),
                "victim", "Victim", null, 1);
        when(options.findDefinitionIdById(victim.getId()))
                .thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(victim.getId())).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.deleteAttributeValue(victim.getId());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
        verify(options, never()).delete(any());
    }

    @Test
    void bulkDeleteSkipsTheRowsThatDiedUnderTheLocks() {
        // The same locked-load skip in bulk form — the honest count skips
        // the concurrently deleted row, never a fabricated delete count,
        // and (the round-26 law) never a poisoned transaction.
        CatalogAttributeDefinition parent = choiceParent();
        CatalogAttributeOption victim = CatalogAttributeOption.create(parent.getId(),
                "victim", "Victim", null, 1);
        when(options.findDefinitionIdById(victim.getId()))
                .thenReturn(Optional.of(parent.getId()));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.findByIdForUpdate(victim.getId())).thenReturn(Optional.empty());

        int deleted = service.deleteAttributeValues(java.util.List.of(victim.getId()));

        assertThat(deleted).isZero();
        verify(options, never()).delete(any());
    }

}
