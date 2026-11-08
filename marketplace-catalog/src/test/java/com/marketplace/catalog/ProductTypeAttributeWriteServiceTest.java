package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignmentUpdateOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * {@code productAttributeAssign},
 * {@code productAttributeAssignmentUpdate},
 * {@code productAttributeUnassign} and
 * {@code productTypeReorderAttributes} mutation roots, the agent-2
 * follow-on packet): the owning write use case's unit proof — the
 * JUnit 5 + Mockito style the module's own write-service tests
 * established (no Spring context: the {@code @PreAuthorize} proxy is
 * an integration concern proven by the app module's real-stack
 * tests; the business decisions the boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link ProductTypeAttributeWriteService} reachable through its
 * typed seam — the assign's scope-model mapping (the PRODUCT/VARIANT
 * + variantSelection pair onto the platform's three scopes, the
 * contradictory PRODUCT+variantSelection INVALID, the
 * VARIANT_SELECTION invariants over the CHOICE shape and the
 * two-option floor, the duplicate assignment's ALREADY_EXISTS, the
 * append position's own max+1 derivation), the update's scope move
 * (the PRODUCT-typed immutability, the VARIANT_SELECTION invariants,
 * the idempotent same-scope re-send), the unassign's honest skip, and
 * the reorder's relative-move resolution with the compacted 1..n
 * re-derivation (the out-of-range refusal included).</p>
 */
class ProductTypeAttributeWriteServiceTest {

    private ProductTypeRepository productTypeRepository;
    private CatalogAttributeDefinitionRepository attributeDefinitionRepository;
    private CatalogAttributeOptionRepository attributeOptionRepository;
    private ProductTypeAttributeAssignmentRepository assignmentRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private ProductVariantAttributeValueRepository variantAttributeValues;
    private ProductAttributeValueRepository productAttributeValues;
    private ProductTypeAttributeWriteSpi service;
    private final Authentication caller = new TestingAuthenticationToken("admin-subject", "n/a", "ROLE_ADMIN");

    private ProductType type;

    @BeforeEach
    void setUp() {
        productTypeRepository = mock(ProductTypeRepository.class);
        attributeDefinitionRepository = mock(CatalogAttributeDefinitionRepository.class);
        attributeOptionRepository = mock(CatalogAttributeOptionRepository.class);
        assignmentRepository = mock(ProductTypeAttributeAssignmentRepository.class);
        // The 2026-11-06 #808 round's scope-consistency guard (Major ·
        // Data Integrity): the move verdict walks the type's products'
        // typed variants — manual mocks answer the EMPTY population by
        // default, so every pre-existing test rides the guard's trivial
        // pass (no products, no variants, nothing to re-key) unless a
        // test stubs the affected population explicitly.
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        variantAttributeValues = mock(ProductVariantAttributeValueRepository.class);
        // The 2026-11-07 #808 round-16 review: the unassignment's
        // product-value wall — the mock answers the EMPTY population by
        // default (every pre-existing test rides the trivial pass).
        productAttributeValues = mock(ProductAttributeValueRepository.class);
        service = new ProductTypeAttributeWriteService(productTypeRepository,
                attributeDefinitionRepository, attributeOptionRepository, assignmentRepository,
                productRepository, variantRepository, variantAttributeValues, productAttributeValues);
        type = ProductType.create("widgets", "Widget Schema", null, null);
        // Every write leg rides the locked read now (the 2026-11-05 #808
        // round locked assignAttributes; the 2026-11-06 round locked its
        // three siblings — the assignment writes of one type serialize on
        // the ProductType row). Both stubs stay: other suites' reuse of
        // this setup may read the plain findById — manual mocks carry no
        // strict-stub checking, so the unused twin is inert.
        when(productTypeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(productTypeRepository.findByIdForUpdate(type.getId())).thenReturn(Optional.of(type));
    }

    private static CatalogAttributeDefinition definition(String code, CatalogAttributeType valueType) {
        return CatalogAttributeDefinition.create(code, "Name " + code, null, valueType, null);
    }

    private ProductTypeAttributeAssignment assigned(CatalogAttributeDefinition definition,
                                                    ProductTypeAttributeScope scope, int position) {
        return ProductTypeAttributeAssignment.create(type.getId(), definition.getId(), scope,
                scope == ProductTypeAttributeScope.VARIANT_SELECTION, position);
    }

    // ------------------------------------------------------------------
    // assignAttributes — the scope-model mapping and the invariants
    // ------------------------------------------------------------------

    @Test
    void anUnknownProductTypeAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();

        ProductTypeMutationResult result = service.assignAttributes(unknown,
                List.of(new ProductTypeAttributeAssignOperation(UUID.randomUUID(), "PRODUCT", null)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.NOT_FOUND, "productTypeId");
    }

    @Test
    void aProductTypedAttributeCannotSimultaneouslyBeAVariantSelection() {
        CatalogAttributeDefinition attribute = definition("attr", CatalogAttributeType.TEXT);

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(attribute.getId(), "PRODUCT", true)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "variantSelection");
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void anUnknownAttributeAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(unknown, "PRODUCT", null)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void aVariantSelectionRequiresTheChoiceValueShape() {
        CatalogAttributeDefinition text = definition("text-attr", CatalogAttributeType.TEXT);
        when(attributeDefinitionRepository.findByIdForShare(text.getId())).thenReturn(Optional.of(text));

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(text.getId(), "VARIANT", true)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.INVALID);
    }

    @Test
    void aVariantSelectionRequiresAtLeastTwoOptions() {
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(1L);

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(choice.getId(), "VARIANT", true)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.INVALID);
    }

    @Test
    void aDuplicateAssignmentAnswersTheAlreadyExistsDomainError() {
        CatalogAttributeDefinition attribute = definition("attr", CatalogAttributeType.TEXT);
        when(attributeDefinitionRepository.findByIdForShare(attribute.getId())).thenReturn(Optional.of(attribute));
        when(assignmentRepository.existsByProductTypeIdAndAttributeDefinitionId(type.getId(), attribute.getId()))
                .thenReturn(true);

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(attribute.getId(), "PRODUCT", null)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.ALREADY_EXISTS);
    }

    @Test
    void aDuplicateAttributeWithinOneRequestAnswersTheAlreadyExistsDomainError() {
        // The wave-19 #808 review (Major): the existsBy probe reads the
        // DATABASE only — it cannot see the rows this request has already
        // staged. The in-memory pending ledger refuses the second staging
        // of the same attribute (the unique index would otherwise answer
        // with an unclassified constraint throw).
        CatalogAttributeDefinition attribute = definition("attr", CatalogAttributeType.TEXT);
        when(attributeDefinitionRepository.findByIdForShare(attribute.getId())).thenReturn(Optional.of(attribute));
        when(assignmentRepository.existsByProductTypeIdAndAttributeDefinitionId(type.getId(), attribute.getId()))
                .thenReturn(false);

        ProductTypeMutationResult result = service.assignAttributes(type.getId(), List.of(
                new ProductTypeAttributeAssignOperation(attribute.getId(), "PRODUCT", null),
                new ProductTypeAttributeAssignOperation(attribute.getId(), "PRODUCT", null)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.ALREADY_EXISTS, "id");
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void twoAssignmentsInTheSameScopeWithinOneRequestRideRunningPositions() {
        // The wave-19 #808 review (Major): the append position is derived
        // per (product type, scope) bucket — two operations staged in the
        // same request read the same database max, so the in-memory
        // running position gives them max+1 and max+2 (the unique index
        // would otherwise refuse the saveAll with a constraint throw).
        CatalogAttributeDefinition first = definition("first-attr", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition second = definition("second-attr", CatalogAttributeType.TEXT);
        when(attributeDefinitionRepository.findByIdForShare(first.getId())).thenReturn(Optional.of(first));
        when(attributeDefinitionRepository.findByIdForShare(second.getId())).thenReturn(Optional.of(second));
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of());
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.assignAttributes(type.getId(), List.of(
                new ProductTypeAttributeAssignOperation(first.getId(), "PRODUCT", null),
                new ProductTypeAttributeAssignOperation(second.getId(), "PRODUCT", null)));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository).saveAll(argThat((List<ProductTypeAttributeAssignment> saved) ->
                saved.size() == 2
                        && saved.get(0).getAttributeDefinitionId().equals(first.getId())
                        && saved.get(0).getPosition() == 1
                        && saved.get(1).getAttributeDefinitionId().equals(second.getId())
                        && saved.get(1).getPosition() == 2));
    }

    @Test
    void theHappyAssignDerivesTheScopeAndTheAppendPosition() {
        CatalogAttributeDefinition text = definition("text-attr", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        // The 2026-11-05 #809 round (Minor · Test Quality): the existing
        // roster's attribute is a THIRD definition — the previous fixture
        // pre-assigned `text` itself, so the "happy" path silently rode a
        // re-assign of an already-assigned attribute (the duplicate probe
        // is a separate stub, which is why it never fired — an
        // inconsistent fixture). The position derivation stays measured
        // over the same starting shape: one existing PRODUCT-scope row at
        // position 1.
        CatalogAttributeDefinition size = definition("size-attr", CatalogAttributeType.TEXT);
        when(attributeDefinitionRepository.findByIdForShare(text.getId())).thenReturn(Optional.of(text));
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(3L);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assigned(size, ProductTypeAttributeScope.PRODUCT, 1)));
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.assignAttributes(type.getId(), List.of(
                new ProductTypeAttributeAssignOperation(text.getId(), "PRODUCT", null),
                new ProductTypeAttributeAssignOperation(choice.getId(), "VARIANT", true)));

        assertThat(result.success()).isTrue();
        assertThat(result.productType().slug()).isEqualTo("widgets");
        verify(assignmentRepository).saveAll(argThat((List<ProductTypeAttributeAssignment> saved) ->
                saved.size() == 2
                        && saved.get(0).getScope() == ProductTypeAttributeScope.PRODUCT
                        && !saved.get(0).isRequired()
                        && saved.get(0).getPosition() == 2
                        && saved.get(1).getScope() == ProductTypeAttributeScope.VARIANT_SELECTION
                        && saved.get(1).isRequired()
                        && saved.get(1).getPosition() == 1));
    }

    // ------------------------------------------------------------------
    // updateAssignments — the scope move
    // ------------------------------------------------------------------

    @Test
    void aProductTypedAssignmentMembershipIsImmutable() {
        CatalogAttributeDefinition attribute = definition("attr", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment assignment = assigned(attribute,
                ProductTypeAttributeScope.PRODUCT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assignment));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(attribute.getId(), true)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.INVALID);
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void theVariantSelectionMembershipMoveEnforcesTheInvariants() {
        CatalogAttributeDefinition text = definition("text-attr", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment assignment = assigned(text, ProductTypeAttributeScope.VARIANT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assignment));
        when(attributeDefinitionRepository.findByIdForShare(text.getId())).thenReturn(Optional.of(text));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(text.getId(), true)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.INVALID);
    }

    @Test
    void theHappyMembershipMoveWritesTheScopeAndItsRequiredLaw() {
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment assignment = assigned(choice, ProductTypeAttributeScope.VARIANT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assignment));
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true)));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository).saveAll(argThat((List<ProductTypeAttributeAssignment> saved) ->
                saved.get(0).getScope() == ProductTypeAttributeScope.VARIANT_SELECTION
                        && saved.get(0).isRequired()));
    }

    @Test
    void theScopeMoveRepositionsInsideTheTargetScopeBeforeTheWrite() {
        // The wave-19 #808 review (Major): the moved row's position is
        // derived from the TARGET scope (its own max+1) — the old VARIANT
        // position would collide with the target scope's own position-1
        // row and the unique index would refuse the UPDATE with an
        // unclassified constraint throw.
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment moved = assigned(choice, ProductTypeAttributeScope.VARIANT, 1);
        ProductTypeAttributeAssignment targetHolder = assigned(
                definition("other-choice", CatalogAttributeType.CHOICE),
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(moved, targetHolder));
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true)));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository).saveAll(argThat((List<ProductTypeAttributeAssignment> saved) ->
                saved.size() == 1
                        && saved.get(0).getId().equals(moved.getId())
                        && saved.get(0).getScope() == ProductTypeAttributeScope.VARIANT_SELECTION
                        && saved.get(0).getPosition() == 2));
    }

    @Test
    void theSameScopeReSendIsTheIdempotentNoOp() {
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment assignment = assigned(choice,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assignment));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true)));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------
    // unassignAttributes — the honest skip
    // ------------------------------------------------------------------

    @Test
    void theUnassignDeletesTheKnownAssignmentsAndSkipsTheUnknown() {
        CatalogAttributeDefinition attribute = definition("attr", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment assignment = assigned(attribute,
                ProductTypeAttributeScope.PRODUCT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assignment));

        ProductTypeMutationResult result = service.unassignAttributes(type.getId(),
                List.of(attribute.getId(), UUID.randomUUID()));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository).delete(assignment);
    }

    // ------------------------------------------------------------------
    // reorderAttributes — the relative-move resolution
    // ------------------------------------------------------------------

    @Test
    void theReorderResolvesTheRelativeMovesWithCompactedPositions() {
        CatalogAttributeDefinition first = definition("attr-one", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition second = definition("attr-two", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition third = definition("attr-three", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment one = assigned(first, ProductTypeAttributeScope.PRODUCT, 1);
        ProductTypeAttributeAssignment two = assigned(second, ProductTypeAttributeScope.PRODUCT, 2);
        ProductTypeAttributeAssignment three = assigned(third, ProductTypeAttributeScope.PRODUCT, 3);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(one, two, three));
        // The stub snapshots each write's positions AT INVOCATION TIME: both
        // saveAll calls carry the SAME mutable list, so an argThat-only proof
        // would see the phase-2 finals on both (the parked write's absence
        // would pass unnoticed). The captured sequence is the real two-phase
        // evidence — park (4, 5, 6), then the dense finals (1, 2, 3).
        List<List<Integer>> savedPositions = new ArrayList<>();
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ProductTypeAttributeAssignment> saved = invocation.getArgument(0);
            savedPositions.add(saved.stream()
                    .map(ProductTypeAttributeAssignment::getPosition)
                    .toList());
            return saved;
        });

        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "PRODUCT",
                List.of(new AttributeReorderMove(third.getId(), -2)));

        assertThat(result.success()).isTrue();
        // The two-phase rewrite the live-position unique index demands:
        // the park write lands first (the whole bucket parked beyond the
        // current max — 4, 5, 6), the flush separates the phases, and the
        // final dense order lands second (1, 2, 3) — both writes carry the
        // same list reference, so the phase-2 finals are what the argThat
        // matcher sees on BOTH invocations; the snapshot sequence and the
        // in-order verification are the proof the parked write happened.
        verify(assignmentRepository, times(2)).saveAll(argThat((List<ProductTypeAttributeAssignment> saved) ->
                saved.size() == 3
                        && saved.get(0).getAttributeDefinitionId().equals(third.getId())
                        && saved.get(0).getPosition() == 1
                        && saved.get(1).getAttributeDefinitionId().equals(first.getId())
                        && saved.get(1).getPosition() == 2
                        && saved.get(2).getAttributeDefinitionId().equals(second.getId())
                        && saved.get(2).getPosition() == 3));
        assertThat(savedPositions).containsExactly(List.of(4, 5, 6), List.of(1, 2, 3));
        InOrder writes = inOrder(assignmentRepository);
        writes.verify(assignmentRepository).saveAll(any());
        writes.verify(assignmentRepository).flush();
        writes.verify(assignmentRepository).saveAll(any());
    }

    @Test
    void theVariantFamilyReorderParksBeyondBothTheCurrentMaximumAndTheFinalRange() {
        // The 2026-11-05 #808 round-3 review (Major · Functional Correctness,
        // repeat): the VARIANT-family bucket merges TWO per-scope-numbered
        // position spaces, so the merged list holds MORE members than the
        // largest stored position (two attributes per scope: n=4, max=2).
        // A park range starting at max+1 (3..6) overlapped the final 1..4
        // range, and Hibernate's update-flush order (NOT the saveAll list
        // order) could transiently duplicate a (product_type_id, scope,
        // position) row against the live unique index. The park must start
        // beyond BOTH bounds — here at max(2, 4)+1 = 5.
        CatalogAttributeDefinition vA = definition("var-a", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition vB = definition("var-b", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition sA = definition("sel-a", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition sB = definition("sel-b", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment va = assigned(vA, ProductTypeAttributeScope.VARIANT, 1);
        ProductTypeAttributeAssignment vb = assigned(vB, ProductTypeAttributeScope.VARIANT, 2);
        ProductTypeAttributeAssignment sa = assigned(sA, ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        ProductTypeAttributeAssignment sb = assigned(sB, ProductTypeAttributeScope.VARIANT_SELECTION, 2);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(va, sa, vb, sb));
        List<List<Integer>> savedPositions = new ArrayList<>();
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ProductTypeAttributeAssignment> saved = invocation.getArgument(0);
            savedPositions.add(saved.stream()
                    .map(ProductTypeAttributeAssignment::getPosition)
                    .toList());
            return saved;
        });

        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "VARIANT",
                List.of(new AttributeReorderMove(vB.getId(), -1)));

        assertThat(result.success()).isTrue();
        // The park write lands ENTIRELY beyond the final range: every parked
        // position >= max(currentMax=2, orderedSize=4) + 1 = 5 — no parked
        // position can collide with any final 1..4 write under ANY flush
        // ordering. The finals are the dense merged order.
        assertThat(savedPositions).hasSize(2);
        assertThat(savedPositions.get(0)).allSatisfy(position -> assertThat(position).isGreaterThanOrEqualTo(5));
        assertThat(savedPositions.get(0)).doesNotHaveDuplicates();
        assertThat(savedPositions.get(1)).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void theVariantFamilyReorderRidesTheCanonicalPositionFirstOrder() {
        // The 2026-11-06 #808 round (Minor · Functional Correctness): the
        // merged VARIANT-family bucket's canonical order is POSITION-first
        // (scope as the tie-break), NOT the repository's (scope, position)
        // read. A previous reorder wrote interleaved positions across the
        // two scopes (vA=1, sA=2, vB=3, sB=4); the repository read returns
        // them scope-at-a-time (vA, vB, sA, sB), so a shift applied on the
        // repository order moved the WRONG neighbor and a round-trip read
        // a different order than the first reorder wrote. The reorder must
        // resolve moves on the canonical order.
        CatalogAttributeDefinition vA = definition("var-a", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition vB = definition("var-b", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition sA = definition("sel-a", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition sB = definition("sel-b", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment va = assigned(vA, ProductTypeAttributeScope.VARIANT, 1);
        ProductTypeAttributeAssignment vb = assigned(vB, ProductTypeAttributeScope.VARIANT, 3);
        ProductTypeAttributeAssignment sa = assigned(sA, ProductTypeAttributeScope.VARIANT_SELECTION, 2);
        ProductTypeAttributeAssignment sb = assigned(sB, ProductTypeAttributeScope.VARIANT_SELECTION, 4);
        // The repository's own (scope, position) order — scope-at-a-time.
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(va, vb, sa, sb));
        List<List<ProductTypeAttributeAssignment>> writes = new ArrayList<>();
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ProductTypeAttributeAssignment> saved = invocation.getArgument(0);
            writes.add(new ArrayList<>(saved));
            return saved;
        });

        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "VARIANT",
                List.of(new AttributeReorderMove(vB.getId(), -1)));

        assertThat(result.success()).isTrue();
        // Two passes: the park write, then the final dense write.
        assertThat(writes).hasSize(2);
        List<ProductTypeAttributeAssignment> finalWrite = writes.get(1);
        Map<String, Integer> written = new java.util.HashMap<>();
        for (ProductTypeAttributeAssignment row : finalWrite) {
            written.put(row.getAttributeDefinitionId().equals(vA.getId()) ? "var-a"
                    : row.getAttributeDefinitionId().equals(vB.getId()) ? "var-b"
                    : row.getAttributeDefinitionId().equals(sA.getId()) ? "sel-a" : "sel-b",
                    row.getPosition());
        }
        // Canonical order [vA, sA, vB, sB] with vB shifted -1 lands at
        // [vA, vB, sA, sB] — the finals: vA=1, vB=2, sA=3, sB=4. On the
        // repository's scope-at-a-time order the same shift would have
        // produced [vB, vA, sA, sB] (vB=1, vA=2, sA=3, sB=4) — the wrong
        // neighbor.
        assertThat(written).containsEntry("var-a", 1).containsEntry("var-b", 2)
                .containsEntry("sel-a", 3).containsEntry("sel-b", 4);
    }

    @Test
    void anOutOfRangeMoveAnswersTheInvalidDomainError() {
        CatalogAttributeDefinition first = definition("attr-one", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment one = assigned(first, ProductTypeAttributeScope.PRODUCT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(one));

        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "PRODUCT",
                List.of(new AttributeReorderMove(first.getId(), 1)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "sortOrder");
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void anUnknownAttributeInTheBucketAnswersTheNotFoundDomainError() {
        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "PRODUCT",
                List.of(new AttributeReorderMove(UUID.randomUUID(), 1)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void anUnknownBucketTypeAnswersTheInvalidDomainError() {
        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "OTHER",
                List.of(new AttributeReorderMove(UUID.randomUUID(), 1)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "type");
    }

    @Test
    void theZeroShiftIsTheIdempotentNoOp() {
        CatalogAttributeDefinition first = definition("attr-one", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment one = assigned(first, ProductTypeAttributeScope.PRODUCT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(one));

        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "PRODUCT",
                List.of(new AttributeReorderMove(first.getId(), 0)));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void theUnassignRefusesWhenALiveVariantCarriesTheAssignment() {
        // The 2026-11-06 #808 round review (Major · Data Integrity): the
        // whole request resolves FIRST — an assignment carrying live
        // variant values answers the classified INVALID refusal and
        // NOTHING is deleted (the unassignment's remove-shape of the
        // scope-move guard's own law).
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment held = assigned(choice,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        ProductTypeAttributeAssignment free = assigned(
                definition("free-choice", CatalogAttributeType.CHOICE),
                ProductTypeAttributeScope.PRODUCT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(held, free));
        when(variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(true);

        ProductTypeMutationResult result = service.unassignAttributes(type.getId(),
                List.of(choice.getId(), free.getId()));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "id");
        verify(assignmentRepository, never()).delete(any(ProductTypeAttributeAssignment.class));
    }

    @Test
    void theUnassignRefusesWhenALiveProductCarriesTheAssignment() {
        // The 2026-11-07 #808 round-16 review (Major · Data Integrity &
        // Integration): the PRODUCT-scope twin of the same wall — the
        // variant population is clean, but a live product value bound to
        // the addressed assignment answers the classified INVALID refusal
        // and NOTHING is deleted (unassigning it would soft-delete the
        // assignment and strand the product on the dead assignment's
        // re-validation refusal — the REST remove path's own law).
        CatalogAttributeDefinition material = definition("material", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment held = assigned(material,
                ProductTypeAttributeScope.PRODUCT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(held));
        when(productAttributeValues.existsLiveProductValueForAssignmentsOrOptions(
                org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(true);

        ProductTypeMutationResult result = service.unassignAttributes(type.getId(),
                List.of(material.getId()));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "id");
        verify(assignmentRepository, never()).delete(any(ProductTypeAttributeAssignment.class));
    }

    // ------------------------------------------------------------------
    // updateAssignments — the 2026-11-06 #808 round's own verdicts
    // ------------------------------------------------------------------

    @Test
    void aRepeatedAttributeIdWithinOneUpdateAnswersTheInvalidDomainError() {
        // The 2026-11-06 #808 round review (Minor · Functional
        // Correctness): ONE operation per attribute per call — the
        // repeated id read the same deferred (not-yet-mutated) row twice
        // and staged two moves for one entity; the duplicate now answers
        // the classified INVALID refusal, never a silent first/last-wins.
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment assignment = assigned(choice, ProductTypeAttributeScope.VARIANT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assignment));
        // The first operation's own VARIANT_SELECTION gate passes (the
        // CHOICE shape and the two-option floor) — the ONLY error left is
        // the duplicate itself.
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);

        ProductTypeMutationResult result = service.updateAssignments(type.getId(), List.of(
                new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true),
                new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), false)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "id");
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void theSelectionMoveRefusesWhenATypedVariantLacksTheMovedAttribute() {
        // The 2026-11-06 #808 round review (Major · Data Integrity):
        // the move INTO the selection schema re-keys the affected typed
        // variants or refuses — a variant without a value for the moved
        // assignment can never cover the post-move selection set, so the
        // move answers the classified refusal (the migration demand is
        // named by SKU) and NOTHING mutates.
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment moved = assigned(choice, ProductTypeAttributeScope.VARIANT, 1);
        ProductTypeAttributeAssignment existing = assigned(
                definition("existing-choice", CatalogAttributeType.CHOICE),
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(moved, existing));
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);
        Product product = Product.register("cat-code", "Affected", null, 1000L, "SAR",
                UUID.randomUUID(), type.getId());
        ProductVariant typed = ProductVariant.register(product, "sku-a", "A", 900L, "old-shape-key");
        // The typed variant covers ONLY the existing selection assignment
        // — the moved assignment has no value at all.
        when(productRepository.findByProductTypeId(type.getId())).thenReturn(List.of(product));
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId())).thenReturn(List.of(typed));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(typed.getId()))
                .thenReturn(List.of(ProductVariantAttributeValue.create(typed.getId(),
                        new CatalogAttributeValueInput(existing.getId(), null, null, null, UUID.randomUUID()))));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "id");
        assertThat(result.errors().get(0).message()).contains("sku-a");
        verify(assignmentRepository, never()).saveAll(any());
        verify(variantRepository, never()).saveAll(any());
    }

    @Test
    void theSelectionMoveOutRefusesWhenTheRekeyCollidesTwoVariants() {
        // The same round: moving an attribute OUT of the selection schema
        // re-derives every typed variant's key over the SMALLER set — two
        // variants that differed ONLY in the moved attribute collapse
        // onto the same combination, and the move answers the classified
        // refusal naming both SKUs instead of dying on the raw unique
        // index.
        CatalogAttributeDefinition kept = definition("kept-choice", CatalogAttributeType.CHOICE);
        CatalogAttributeDefinition leaving = definition("leaving-choice", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment keptAssignment = assigned(kept,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        ProductTypeAttributeAssignment leavingAssignment = assigned(leaving,
                ProductTypeAttributeScope.VARIANT_SELECTION, 2);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(keptAssignment, leavingAssignment));
        Product product = Product.register("cat-code", "Affected", null, 1000L, "SAR",
                UUID.randomUUID(), type.getId());
        ProductVariant first = ProductVariant.register(product, "sku-one", "One", 900L, "key-one");
        ProductVariant second = ProductVariant.register(product, "sku-two", "Two", 900L, "key-two");
        UUID sharedOption = UUID.randomUUID();
        when(productRepository.findByProductTypeId(type.getId())).thenReturn(List.of(product));
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId()))
                .thenReturn(List.of(first, second));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(first.getId()))
                .thenReturn(List.of(
                        ProductVariantAttributeValue.create(first.getId(), new CatalogAttributeValueInput(
                                keptAssignment.getId(), null, null, null, sharedOption)),
                        ProductVariantAttributeValue.create(first.getId(), new CatalogAttributeValueInput(
                                leavingAssignment.getId(), null, null, null, UUID.randomUUID()))));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(second.getId()))
                .thenReturn(List.of(
                        ProductVariantAttributeValue.create(second.getId(), new CatalogAttributeValueInput(
                                keptAssignment.getId(), null, null, null, sharedOption)),
                        ProductVariantAttributeValue.create(second.getId(), new CatalogAttributeValueInput(
                                leavingAssignment.getId(), null, null, null, UUID.randomUUID()))));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(leaving.getId(), false)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "id");
        assertThat(result.errors().get(0).message()).contains("sku-one").contains("sku-two");
        verify(assignmentRepository, never()).saveAll(any());
        verify(variantRepository, never()).saveAll(any());
    }

    @Test
    void theHappySelectionMoveRekeysTheTypedVariantsOverThePostMoveSchema() {
        // The same round: the verdict's PASSING leg — the typed variants'
        // keys are re-derived over the post-move selection schema (the
        // registration path's own VariantCombinationKeys bytes), so a
        // fresh registration under the new schema and a re-keyed veteran
        // answer the SAME key for the same combination — the duplicate-
        // registration hole the finding proved is closed.
        CatalogAttributeDefinition kept = definition("kept-choice", CatalogAttributeType.CHOICE);
        CatalogAttributeDefinition moved = definition("moved-choice", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment keptAssignment = assigned(kept,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        ProductTypeAttributeAssignment movedAssignment = assigned(moved,
                ProductTypeAttributeScope.VARIANT, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(keptAssignment, movedAssignment));
        when(attributeDefinitionRepository.findByIdForShare(moved.getId())).thenReturn(Optional.of(moved));
        when(attributeOptionRepository.countByAttributeDefinitionId(moved.getId())).thenReturn(2L);
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(variantRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Product product = Product.register("cat-code", "Affected", null, 1000L, "SAR",
                UUID.randomUUID(), type.getId());
        ProductVariant typed = ProductVariant.register(product, "sku-a", "A", 900L, "old-shape-key");
        UUID keptOption = UUID.randomUUID();
        UUID movedOption = UUID.randomUUID();
        when(productRepository.findByProductTypeId(type.getId())).thenReturn(List.of(product));
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId())).thenReturn(List.of(typed));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(typed.getId()))
                .thenReturn(List.of(
                        ProductVariantAttributeValue.create(typed.getId(), new CatalogAttributeValueInput(
                                keptAssignment.getId(), null, null, null, keptOption)),
                        ProductVariantAttributeValue.create(typed.getId(), new CatalogAttributeValueInput(
                                movedAssignment.getId(), null, null, null, movedOption))));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(moved.getId(), true)));

        assertThat(result.success()).isTrue();
        // The re-derived key: the post-move selection set is BOTH
        // assignments — the registration path's own derivation over the
        // same values answers the same bytes.
        String expected = VariantCombinationKeys.canonical(
                List.of(keptAssignment, movedAssignment),
                java.util.Map.of(keptAssignment.getId(),
                        new CatalogAttributeValueInput(keptAssignment.getId(), null, null, null, keptOption),
                        movedAssignment.getId(),
                        new CatalogAttributeValueInput(movedAssignment.getId(), null, null, null, movedOption)));
        verify(variantRepository).saveAll(argThat((List<ProductVariant> saved) ->
                saved.size() == 1
                        && saved.get(0).getOptionCombinationKey().equals(expected)
                        && !saved.get(0).getOptionCombinationKey().equals("old-shape-key")));
    }

    @Test
    void theLastSelectionMoveOutTurnsEveryTypedVariantFreeForm() {
        // The 2026-11-06 #808 round-3 review (Major · Functional
        // Correctness): when the post-move selection set becomes EMPTY,
        // canonical answers null for every variant — the previous
        // putIfAbsent treated the second null key as a COLLISION and
        // refused the move, although the partial unique index excludes
        // null keys by its own predicate. The move now succeeds and every
        // typed variant recombines to the free-form null key.
        CatalogAttributeDefinition leaving = definition("leaving-choice", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment leavingAssignment = assigned(leaving,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(leavingAssignment));
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(variantRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Product product = Product.register("cat-code", "Affected", null, 1000L, "SAR",
                UUID.randomUUID(), type.getId());
        ProductVariant first = ProductVariant.register(product, "sku-one", "One", 900L, "key-one");
        ProductVariant second = ProductVariant.register(product, "sku-two", "Two", 900L, "key-two");
        when(productRepository.findByProductTypeId(type.getId())).thenReturn(List.of(product));
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId()))
                .thenReturn(List.of(first, second));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(first.getId()))
                .thenReturn(List.of(ProductVariantAttributeValue.create(first.getId(),
                        new CatalogAttributeValueInput(leavingAssignment.getId(), null, null, null,
                                UUID.randomUUID()))));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(second.getId()))
                .thenReturn(List.of(ProductVariantAttributeValue.create(second.getId(),
                        new CatalogAttributeValueInput(leavingAssignment.getId(), null, null, null,
                                UUID.randomUUID()))));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(leaving.getId(), false)));

        assertThat(result.success()).as("two keyed variants must NOT answer a collision the null-excluding"
                + " index cannot produce").isTrue();
        verify(variantRepository).saveAll(argThat((List<ProductVariant> saved) ->
                saved.size() == 2 && saved.stream().allMatch(row -> row.getOptionCombinationKey() == null)));
    }

    @Test
    void aMovedOutSelectionAttributeRekeysItsVariantsBackInOnReturn() {
        // The same round's round-trip: the move OUT turns the typed
        // variants free-form (null keys), and the values NEVER leave the
        // rows — the move BACK IN re-derives the very same keys (the
        // null-key variant is no longer skipped: the coverage verdict and
        // the re-derivation own it again).
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment selection = assigned(choice,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(selection));
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(variantRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Product product = Product.register("cat-code", "Affected", null, 1000L, "SAR",
                UUID.randomUUID(), type.getId());
        UUID option = UUID.randomUUID();
        ProductVariant typed = ProductVariant.register(product, "sku-a", "A", 900L, "old-shape-key");
        when(productRepository.findByProductTypeId(type.getId())).thenReturn(List.of(product));
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId())).thenReturn(List.of(typed));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(typed.getId()))
                .thenReturn(List.of(ProductVariantAttributeValue.create(typed.getId(),
                        new CatalogAttributeValueInput(selection.getId(), null, null, null, option))));

        // Leg 1 — the move OUT: the typed variant recombines to the null
        // free-form key.
        ProductTypeMutationResult movedOut = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), false)));
        assertThat(movedOut.success()).isTrue();
        assertThat(typed.getOptionCombinationKey()).isNull();

        // Leg 2 — the move BACK IN: the null-key variant (whose values
        // stayed) re-derives the same combination key the registration
        // path computes over the same schema.
        ProductTypeMutationResult movedIn = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true)));
        assertThat(movedIn.success()).isTrue();
        String expected = VariantCombinationKeys.canonical(List.of(selection),
                java.util.Map.of(selection.getId(),
                        new CatalogAttributeValueInput(selection.getId(), null, null, null, option)));
        assertThat(typed.getOptionCombinationKey()).isEqualTo(expected);
    }

    @Test
    void theMoveOutOfSelectionPreservesTheRequiredValue() {
        // The 2026-11-06 #808 round-3 review (Minor · Functional
        // Correctness): required is the row's OWN independent contract —
        // VARIANT_SELECTION forces true, and the move OUT PRESERVES the
        // current value (the previous shape rewrote it to false,
        // silently loosening a required attribute into an optional one).
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment selection = assigned(choice,
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(selection));
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), false)));

        assertThat(result.success()).isTrue();
        verify(assignmentRepository).saveAll(argThat((List<ProductTypeAttributeAssignment> saved) ->
                saved.get(0).getScope() == ProductTypeAttributeScope.VARIANT
                        && saved.get(0).isRequired()));
    }

    @Test
    void aNullOperationElementAnswersThePerItemRequiredErrorNeverTheBatchNpe() {
        // The 2026-11-08 #808 round-21 review (Minor · Functional
        // Correctness, additional comment): updateAssignments and
        // reorderAttributes own the SAME null-element law assignAttributes
        // and the bulk-create paths established — a null element answers
        // the per-item REQUIRED entry, never the NullPointerException
        // that aborts the whole batch.
        java.util.List<UUID> none = java.util.List.of();
        ProductTypeMutationResult updated = service.updateAssignments(type.getId(),
                java.util.Arrays.asList((ProductTypeAttributeAssignmentUpdateOperation) null));
        assertThat(updated.success()).isFalse();
        assertThat(updated.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.REQUIRED, "operation");

        ProductTypeMutationResult reordered = service.reorderAttributes(type.getId(), "VARIANT",
                java.util.Arrays.asList((AttributeReorderMove) null));
        assertThat(reordered.success()).isFalse();
        assertThat(reordered.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.REQUIRED, "move");
    }

    @Test
    void theBatchDefinitionShareLocksComeInOneSortedPass() {
        // The 2026-11-08 #808 round-21 review (Minor · Stability &
        // Availability, the lock-order law): the batch's definition
        // FOR-SHARE reads walk ONE sorted pass — the caller's list order
        // never reaches the lock sequence (a caller naming two
        // definitions in DESCENDING uuid order still locks them in the
        // ASCENDING order every bulk writer in this module owns; the
        // reverse order could deadlock against a concurrent
        // unassignAttributes holding the first row FOR UPDATE).
        CatalogAttributeDefinition low = definition("low-attr", CatalogAttributeType.NUMBER);
        CatalogAttributeDefinition high = definition("high-attr", CatalogAttributeType.NUMBER);
        UUID first = java.util.stream.Stream.of(low.getId(), high.getId())
                .min(java.util.UUID::compareTo).get();
        UUID second = java.util.stream.Stream.of(low.getId(), high.getId())
                .max(java.util.UUID::compareTo).get();
        when(attributeDefinitionRepository.findByIdForShare(first)).thenReturn(Optional.of(first.equals(low.getId()) ? low : high));
        when(attributeDefinitionRepository.findByIdForShare(second)).thenReturn(Optional.of(second.equals(low.getId()) ? low : high));
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(java.util.List.of());
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // The caller names the HIGHER uuid first — the locks still come
        // in the sorted order.
        java.util.List<ProductTypeAttributeAssignOperation> ops = second.equals(high.getId())
                ? java.util.List.of(new ProductTypeAttributeAssignOperation(high.getId(), "VARIANT", false),
                        new ProductTypeAttributeAssignOperation(low.getId(), "VARIANT", false))
                : java.util.List.of(new ProductTypeAttributeAssignOperation(low.getId(), "VARIANT", false),
                        new ProductTypeAttributeAssignOperation(high.getId(), "VARIANT", false));

        ProductTypeMutationResult result = service.assignAttributes(type.getId(), ops);

        assertThat(result.success()).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(attributeDefinitionRepository);
        order.verify(attributeDefinitionRepository).findByIdForShare(first);
        order.verify(attributeDefinitionRepository).findByIdForShare(second);
    }

    @Test
    void assignAttributesIsRefusedWhileAPublishedProductCarriesAnyLiveVariant() {
        // The 2026-11-06 #808 round-3 review (Major · Data Integrity &
        // Integration), widened by the round-5 review: a NEW selection
        // assignment's value cannot exist on any variant yet — a published
        // (or suspended) product carrying ANY live variant (the round-5
        // scenario's FREE-FORM NULL-KEY row included: a selection-free
        // type publishes with one) would keep its variants short of the
        // new selection and the shelf never re-passes the publication
        // gate. The variants migrate first; DRAFT products stay eligible
        // (the publish gate owns them).
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);
        when(variantRepository.existsPublishedShelfVariantForType(type.getId())).thenReturn(true);

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(choice.getId(), "VARIANT", true)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "id");
        assertThat(result.errors().get(0).message()).contains("migrate the variants first");
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void assignAttributesAnswersTheClassifiedExhaustionWhenTheBucketSitsAtTheCeiling() {
        // The 2026-11-07 #808 round-19 review (the overflow family): the
        // REST assign surface accepts a client-supplied position with
        // only a @Min(0) guard — a stored Integer.MAX_VALUE in the
        // bucket makes the derived max+1 wrap negative. The sentinel
        // answers the classified INVALID error naming the compaction
        // path (the bucket's reorder), never the entity's raw
        // negative-position throw.
        CatalogAttributeDefinition text = definition("text-attr", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition fresh = definition("fresh-attr", CatalogAttributeType.TEXT);
        ProductTypeAttributeAssignment ceiling = assigned(text, ProductTypeAttributeScope.VARIANT,
                Integer.MAX_VALUE);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(ceiling));
        when(attributeDefinitionRepository.findByIdForShare(text.getId())).thenReturn(Optional.of(text));
        when(attributeDefinitionRepository.findByIdForShare(fresh.getId())).thenReturn(Optional.of(fresh));

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(fresh.getId(), "VARIANT", false)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "position");
        assertThat(result.errors().get(0).message()).contains("reorder");
        verify(assignmentRepository, never()).saveAll(any());
    }

    @Test
    void theSelectionMoveStagesANullKeyForAnUncoveredArchivedVariant() {
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness): the ARCHIVED row is terminal (the pinned update
        // contract refuses it), so the migrate-first refusal is a DEAD
        // END for it — an uncovered ARCHIVED row leaves the combination
        // space instead (the null-key recombine, the free-form
        // population the partial unique index permits), never a refusal
        // that blocks the shelf-eligible migration.
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        ProductTypeAttributeAssignment moved = assigned(choice, ProductTypeAttributeScope.VARIANT, 1);
        ProductTypeAttributeAssignment existing = assigned(
                definition("existing-choice", CatalogAttributeType.CHOICE),
                ProductTypeAttributeScope.VARIANT_SELECTION, 1);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(moved, existing));
        when(attributeDefinitionRepository.findByIdForShare(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(2L);
        Product product = Product.register("cat-code", "Affected", null, 1000L, "SAR",
                UUID.randomUUID(), type.getId());
        ProductVariant archived = ProductVariant.register(product, "sku-archived", "Archived", 900L, "old-key");
        archived.archive();
        // The archived variant covers ONLY the existing selection
        // assignment — the moved assignment has no value at all.
        when(productRepository.findByProductTypeId(type.getId())).thenReturn(List.of(product));
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId())).thenReturn(List.of(archived));
        when(variantAttributeValues.findAllByProductVariantIdOrderByAssignmentIdAsc(archived.getId()))
                .thenReturn(List.of(ProductVariantAttributeValue.create(archived.getId(),
                        new CatalogAttributeValueInput(existing.getId(), null, null, null, UUID.randomUUID()))));

        ProductTypeMutationResult result = service.updateAssignments(type.getId(),
                List.of(new ProductTypeAttributeAssignmentUpdateOperation(choice.getId(), true)));

        assertThat(result.success()).isTrue();
        // The archived row left the combination space: the null-key
        // recombine (never the migrate-first refusal).
        verify(variantRepository).saveAll(org.mockito.ArgumentMatchers.argThat(
                (java.util.List<ProductVariant> rows) -> rows.size() == 1
                        && rows.get(0).getId().equals(archived.getId())
                        && rows.get(0).getOptionCombinationKey() == null));
    }

}
