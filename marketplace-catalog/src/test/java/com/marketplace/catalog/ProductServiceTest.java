package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * A-17 (C.7 — the M1 store root): the registration and owner-read
 * contracts — the dictionary-membership gate (an unknown category code
 * answers the house 404 BEFORE any write, the L31 discipline), the
 * caller-as-owner rule (the owning provider IS the caller — no ownership
 * argument can be forged), and the R5 owner-read posture (a stranger's
 * read answers the honest 404 — existence itself is private).
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private StoreCategoryRepository storeCategoryRepository;
    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private ProductTypeAttributeAssignmentRepository assignments;
    @Mock
    private CatalogAttributeDefinitionRepository definitions;
    @Mock
    private CatalogAttributeOptionRepository options;
    @Mock
    private ProductAttributeValueRepository attributeValues;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private ProductService service;
    private final UUID caller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, productVariantRepository, storeCategoryRepository,
                productTypeRepository, assignments, definitions, options, attributeValues, currentUserProvider);
    }

    private ProductService.RegisterProductCommand command(String categoryCode) {
        return new ProductService.RegisterProductCommand(
                categoryCode, "Espresso machine, 2-cup", "Pump-driven", 149900L, "SAR");
    }

    private ProductService.RegisterProductCommand typedCommand(String categoryCode, UUID productTypeId) {
        return new ProductService.RegisterProductCommand(
                categoryCode, "Espresso machine, 2-cup", "Pump-driven", 149900L, "SAR", productTypeId);
    }

    private ProductService.RegisterProductCommand typedCommand(String categoryCode, UUID productTypeId,
                                                               List<CatalogAttributeValueInput> values) {
        return new ProductService.RegisterProductCommand(
                categoryCode, "Espresso machine, 2-cup", "Pump-driven", 149900L, "SAR", productTypeId, values);
    }

    @Test
    void registerAnswers404ForUnknownCategoryCode_BeforeAnyWrite() {
        when(storeCategoryRepository.findByCode("home-appliances")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(command("home-appliances"), authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("home-appliances");
    }

    @Test
    void registerSavesWithTheCallerAsOwningProvider() {
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Product product = service.register(command("home-appliances"), authentication);

        assertThat(product.getStoreCategoryCode()).isEqualTo("home-appliances");
        assertThat(product.getProviderId()).as("the owning provider IS the caller").isEqualTo(caller);
        assertThat(product.getPriceMinor()).isEqualTo(149900L);
        assertThat(product.getCurrency()).isEqualTo("SAR");
    }

    @Test
    void registerCanAttachAnExistingProductType() {
        UUID productTypeId = UUID.randomUUID();
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(productTypeRepository.findById(productTypeId)).thenReturn(Optional.of(
                ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product product = service.register(typedCommand("home-appliances", productTypeId), authentication);

        assertThat(product.getProductTypeId()).isEqualTo(productTypeId);
    }

    @Test
    void requiredProductAttributeMustBeSuppliedAndStoredWithItsTypedValue() {
        UUID productTypeId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(productTypeRepository.findById(productTypeId)).thenReturn(Optional.of(
                ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null)));
        ProductTypeAttributeAssignment assignment = ProductTypeAttributeAssignment.create(
                productTypeId, attributeId, ProductTypeAttributeScope.PRODUCT, true, 0);
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId)).thenReturn(List.of(assignment));
        when(definitions.findById(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("material", "Material", "الخامة", CatalogAttributeType.TEXT, null)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogAttributeValueInput input = new CatalogAttributeValueInput(assignmentId, "Cotton", null, null, null);
        // The caller must supply the actual assignment ID, not just the definition ID.
        input = new CatalogAttributeValueInput(assignment.getId(), "Cotton", null, null, null);
        when(attributeValues.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Product product = service.register(typedCommand("home-appliances", productTypeId, List.of(input)), authentication);

        assertThat(product.getProductTypeId()).isEqualTo(productTypeId);
        verify(attributeValues).saveAll(any());
    }

    @Test
    void mismatchedTypedValueIsRejectedBeforeProductWrite() {
        UUID productTypeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(productTypeRepository.findById(productTypeId)).thenReturn(Optional.of(
                ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null)));
        ProductTypeAttributeAssignment assignment = ProductTypeAttributeAssignment.create(
                productTypeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0);
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId)).thenReturn(List.of(assignment));
        when(definitions.findById(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("weight", "Weight", "الوزن", CatalogAttributeType.NUMBER, null)));
        CatalogAttributeValueInput wrongType = new CatalogAttributeValueInput(assignment.getId(), "heavy", null, null, null);

        assertThatThrownBy(() -> service.register(typedCommand("home-appliances", productTypeId, List.of(wrongType)), authentication))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("does not match");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void registerRejectsUnknownProductTypeBeforeProductWrite() {
        UUID unknownType = UUID.randomUUID();
        when(storeCategoryRepository.findByCode("home-appliances"))
                .thenReturn(Optional.of(StoreCategory.register("home-appliances", "Home appliances", "أجهزة منزلية", 0)));
        when(productTypeRepository.findById(unknownType)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(typedCommand("home-appliances", unknownType), authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ProductType");
        org.mockito.Mockito.verify(productRepository, org.mockito.Mockito.never()).save(any(Product.class));
    }

    @Test
    void typedDraftCannotPublishWhenARequiredProductAttributeIsMissing() {
        UUID productTypeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        Product draft = Product.register("home-appliances", "Typed draft", null, 100L, "SAR", caller, productTypeId);
        ProductTypeAttributeAssignment required = ProductTypeAttributeAssignment.create(
                productTypeId, attributeId, ProductTypeAttributeScope.PRODUCT, true, 0);
        when(productRepository.findById(productId)).thenReturn(Optional.of(draft));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(attributeValues.findAllByProductIdOrderByAssignmentIdAsc(productId)).thenReturn(List.of());
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId)).thenReturn(List.of(required));

        assertThatThrownBy(() -> service.publishOwnedProduct(productId, authentication))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("Required product attribute assignment is missing");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void typedDraftCannotPublishBeforeASelectableVariantExists() {
        UUID productTypeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        Product draft = Product.register("home-appliances", "Typed draft", null, 100L, "SAR", caller, productTypeId);
        ProductTypeAttributeAssignment selection = ProductTypeAttributeAssignment.create(
                productTypeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0);
        when(productRepository.findById(productId)).thenReturn(Optional.of(draft));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(attributeValues.findAllByProductIdOrderByAssignmentIdAsc(productId)).thenReturn(List.of());
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId))
                .thenReturn(List.of(selection));
        when(productVariantRepository.findByProductIdOrderBySkuAsc(productId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishOwnedProduct(productId, authentication))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("requires at least one active typed variant");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void ownerReadReturnsTheProductToStrangerNothing() {
        UUID productId = UUID.randomUUID();
        Product owned = Product.register("home-appliances", "t", null, 1L, "SAR", caller);
        when(productRepository.findById(productId)).thenReturn(Optional.of(owned));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);

        assertThat(service.getOwnedProduct(productId, authentication)).isSameAs(owned);
    }

    @Test
    void strangerReadAnswersTheHonest404() {
        UUID productId = UUID.randomUUID();
        Product owned = Product.register("home-appliances", "t", null, 1L, "SAR", caller);
        when(productRepository.findById(productId)).thenReturn(Optional.of(owned));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.getOwnedProduct(productId, authentication))
                .as("existence itself is private (the R5 posture)")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
