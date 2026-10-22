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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

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
    private ProductTypeAttributeWriteSpi service;
    private final Authentication caller = new TestingAuthenticationToken("admin-subject", "n/a", "ROLE_ADMIN");

    private ProductType type;

    @BeforeEach
    void setUp() {
        productTypeRepository = mock(ProductTypeRepository.class);
        attributeDefinitionRepository = mock(CatalogAttributeDefinitionRepository.class);
        attributeOptionRepository = mock(CatalogAttributeOptionRepository.class);
        assignmentRepository = mock(ProductTypeAttributeAssignmentRepository.class);
        service = new ProductTypeAttributeWriteService(productTypeRepository,
                attributeDefinitionRepository, attributeOptionRepository, assignmentRepository);
        type = ProductType.create("widgets", "Widget Schema", null, null);
        when(productTypeRepository.findById(type.getId())).thenReturn(Optional.of(type));
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
        when(attributeDefinitionRepository.findById(text.getId())).thenReturn(Optional.of(text));

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(text.getId(), "VARIANT", true)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.INVALID);
    }

    @Test
    void aVariantSelectionRequiresAtLeastTwoOptions() {
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        when(attributeDefinitionRepository.findById(choice.getId())).thenReturn(Optional.of(choice));
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
        when(attributeDefinitionRepository.findById(attribute.getId())).thenReturn(Optional.of(attribute));
        when(assignmentRepository.existsByProductTypeIdAndAttributeDefinitionId(type.getId(), attribute.getId()))
                .thenReturn(true);

        ProductTypeMutationResult result = service.assignAttributes(type.getId(),
                List.of(new ProductTypeAttributeAssignOperation(attribute.getId(), "PRODUCT", null)));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code)
                .isEqualTo(ProductTypeMutationErrorCode.ALREADY_EXISTS);
    }

    @Test
    void theHappyAssignDerivesTheScopeAndTheAppendPosition() {
        CatalogAttributeDefinition text = definition("text-attr", CatalogAttributeType.TEXT);
        CatalogAttributeDefinition choice = definition("choice-attr", CatalogAttributeType.CHOICE);
        when(attributeDefinitionRepository.findById(text.getId())).thenReturn(Optional.of(text));
        when(attributeDefinitionRepository.findById(choice.getId())).thenReturn(Optional.of(choice));
        when(attributeOptionRepository.countByAttributeDefinitionId(choice.getId())).thenReturn(3L);
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .thenReturn(List.of(assigned(text, ProductTypeAttributeScope.PRODUCT, 1)));
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
        when(attributeDefinitionRepository.findById(text.getId())).thenReturn(Optional.of(text));

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
        when(attributeDefinitionRepository.findById(choice.getId())).thenReturn(Optional.of(choice));
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
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.reorderAttributes(type.getId(), "PRODUCT",
                List.of(new AttributeReorderMove(third.getId(), -2)));

        assertThat(result.success()).isTrue();
        // The park-then-settle rewrite: saveAll rides TWICE (the park pass
        // over the live range, the settle pass over the full compacted
        // order). The captured lists share the SAME mutable entity
        // references, so the captor cannot freeze the parked values —
        // the provable facts are the two-pass interaction and the final
        // settled state of the entities themselves (the same facts the
        // integration leg proves against the real unique index).
        verify(assignmentRepository, times(2)).saveAll(org.mockito.ArgumentMatchers.anyList());
        assertThat(third.getPosition()).isEqualTo(1);
        assertThat(first.getPosition()).isEqualTo(2);
        assertThat(second.getPosition()).isEqualTo(3);
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
}
