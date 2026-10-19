package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0012 (the merchandise model's variant depth) — the variant surface's
 * unit gate: the ownership gates (the owning product's provider or ADMIN,
 * an honest 404 otherwise — the {@code ProductInventoryServiceTest} shape
 * verbatim), the SKU namespace rule (the seller's own space; a collision
 * answers the house 409 with the existing variant named; another seller's
 * identical SKU is free), the service's own copy of provider/currency from
 * the owning product (never client-supplied), the stock guard (the shelf
 * never falls below the reserved set), and the availability machine
 * (SUSPENDED is a toggle, ARCHIVED is terminal).
 *
 * <p><b>The fixture discipline (the house Instancio pattern):</b> the
 * {@code ProductVariant} IS the one stock/reserved/lifecycle record, so
 * every fixture pins its state through Instancio exactly as the product's
 * own test does — the reserved set is the future cart-integration slice's
 * conditional update, materialized directly here, never simulated through
 * {@code restock}.
 */
@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock
    private ProductVariantRepository variantRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private ProductTypeAttributeAssignmentRepository assignments;
    @Mock
    private CatalogAttributeDefinitionRepository definitions;
    @Mock
    private CatalogAttributeOptionRepository options;
    @Mock
    private ProductVariantAttributeValueRepository attributeValues;
    @Mock
    private Authentication authentication;

    private ProductVariantService service;

    private final UUID seller = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductVariantService(variantRepository, productRepository, currentUserProvider,
                assignments, definitions, options, attributeValues);
    }

    /** The owning product — the seller's own live shelf, the register-honest fresh shape. */
    private Product product() {
        return Instancio.of(Product.class)
                .set(field(Product::getProviderId), seller)
                .set(field(Product::getCurrency), "SAR")
                .set(field(Product::getStatus), StoreProductStatus.ACTIVE)
                .set(field(Product::getProductTypeId), null)
                .set(field(Product::getStockQuantity), 0)
                .set(field(Product::getReservedQuantity), 0)
                .create();
    }

    /** The variant — the seller's own live purchasable unit (zero reserved, the fresh shape). */
    private ProductVariant variant(Product product) {
        return Instancio.of(ProductVariant.class)
                .set(field(ProductVariant::getProductId), product.getId())
                .set(field(ProductVariant::getProviderId), seller)
                .set(field(ProductVariant::getStatus), ProductStatus.ACTIVE)
                .set(field(ProductVariant::getStockQuantity), 0)
                .set(field(ProductVariant::getReservedQuantity), 0)
                .create();
    }

    @Test
    void theOwningProviderRegistersAVariantWithTheProductsOwnProviderAndCurrency() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.findByProviderIdAndSku(seller, "ESPRESSO-RED"))
                .thenReturn(Optional.empty());
        when(variantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVariant saved = service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand("ESPRESSO-RED", "Red / 2-cup", 149900),
                authentication);

        // The service's own copy — the ADR's decision 3: the provider and
        // the currency come from the owning product, never the command; the
        // stock starts at zero (the inventory surface owns the shelf).
        assertThat(saved.getProviderId()).isEqualTo(seller);
        assertThat(saved.getCurrency()).isEqualTo("SAR");
        assertThat(saved.getProductId()).isEqualTo(product.getId());
        assertThat(saved.getSku()).isEqualTo("ESPRESSO-RED");
        assertThat(saved.getName()).isEqualTo("Red / 2-cup");
        assertThat(saved.getPriceMinor()).isEqualTo(149900);
        assertThat(saved.getStockQuantity()).isZero();
        assertThat(saved.getReservedQuantity()).isZero();
        assertThat(saved.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void typedVariantRequiresValidSelectableOptionAndProducesCanonicalKey() {
        UUID productTypeId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        Product product = Product.register("typed-goods", "Typed goods", null, 100L, "SAR", seller, productTypeId);
        ProductTypeAttributeAssignment selection = ProductTypeAttributeAssignment.create(
                productTypeId, definitionId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0);
        CatalogAttributeDefinition definition = CatalogAttributeDefinition.create(
                "color", "Color", "اللون", CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption option = CatalogAttributeOption.create(definitionId, "red", "Red", "أحمر", 0);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.findByProviderIdAndSku(seller, "TYPED-RED")).thenReturn(Optional.empty());
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId)).thenReturn(List.of(selection));
        when(definitions.findById(definitionId)).thenReturn(Optional.of(definition));
        when(options.findById(option.getId())).thenReturn(Optional.of(option));
        when(variantRepository.existsByProductIdAndOptionCombinationKey(
                org.mockito.ArgumentMatchers.eq(product.getId()), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(variantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attributeValues.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductVariant saved = service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand("TYPED-RED", "Red", 100,
                        List.of(new CatalogAttributeValueInput(selection.getId(), null, null, null, option.getId()))),
                authentication);

        assertThat(saved.getOptionCombinationKey()).matches("[0-9a-f]{64}");
        verify(attributeValues).saveAll(any());
    }

    @Test
    void duplicateTypedVariantCombinationIsRejectedBeforeWrite() {
        UUID productTypeId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        Product product = Product.register("typed-goods", "Typed goods", null, 100L, "SAR", seller, productTypeId);
        ProductTypeAttributeAssignment selection = ProductTypeAttributeAssignment.create(
                productTypeId, definitionId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0);
        CatalogAttributeDefinition definition = CatalogAttributeDefinition.create(
                "color", "Color", "اللون", CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption option = CatalogAttributeOption.create(definitionId, "red", "Red", "أحمر", 0);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.findByProviderIdAndSku(seller, "DUP-RED")).thenReturn(Optional.empty());
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId)).thenReturn(List.of(selection));
        when(definitions.findById(definitionId)).thenReturn(Optional.of(definition));
        when(options.findById(option.getId())).thenReturn(Optional.of(option));
        when(variantRepository.existsByProductIdAndOptionCombinationKey(
                org.mockito.ArgumentMatchers.eq(product.getId()), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand("DUP-RED", "Another red", 100,
                        List.of(new CatalogAttributeValueInput(selection.getId(), null, null, null, option.getId()))),
                authentication)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("same typed option combination");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void registrationUnderAnUnknownProductAnswersTheHonest404() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(productId,
                new ProductVariantService.RegisterVariantCommand("SKU-1", null, 100), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(variantRepository, never()).save(any());
    }

    @Test
    void aStrangerCannotRegisterUnderSomeoneElsesProduct() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(stranger);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand("SKU-1", null, 100), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(variantRepository, never()).save(any());
    }

    @Test
    void anAdminMayRegisterUnderAnyProduct() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(stranger);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(variantRepository.findByProviderIdAndSku(seller, "ADMIN-SKU"))
                .thenReturn(Optional.empty());
        when(variantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVariant saved = service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand("ADMIN-SKU", null, 100), authentication);

        // The namespace is the OWNING product's seller — the admin acts on
        // the seller's behalf, never in the admin's own namespace.
        assertThat(saved.getProviderId()).isEqualTo(seller);
    }

    @Test
    void aSkuCollisionInTheSellersNamespaceAnswersThe409WithTheCollisionNamed() {
        Product product = product();
        ProductVariant existing = variant(product);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.findByProviderIdAndSku(seller, existing.getSku()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand(existing.getSku(), null, 100),
                authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(existing.getSku())
                .hasMessageContaining(existing.getId().toString());
        verify(variantRepository, never()).save(any());
    }

    @Test
    void theSameSkuIsFreeInAnotherSellersNamespace() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.findByProviderIdAndSku(seller, "RED-XL"))
                .thenReturn(Optional.empty());
        when(variantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVariant saved = service.register(product.getId(),
                new ProductVariantService.RegisterVariantCommand("RED-XL", null, 100), authentication);

        assertThat(saved.getSku()).isEqualTo("RED-XL");
    }

    @Test
    void theShelfNeverFallsBelowTheReservedSet() {
        Product product = product();
        ProductVariant variant = Instancio.of(ProductVariant.class)
                .set(field(ProductVariant::getProviderId), seller)
                .set(field(ProductVariant::getStatus), ProductStatus.ACTIVE)
                .set(field(ProductVariant::getStockQuantity), 3)
                .set(field(ProductVariant::getReservedQuantity), 2)
                .create();
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);

        assertThatThrownBy(() -> service.setStock(variant.getId(), 1, authentication))
                .as("shelf=%d reserved=%d requested=%d",
                        variant.getStockQuantity(), variant.getReservedQuantity(), 1)
                .isInstanceOf(IllegalArgumentException.class);
        verify(variantRepository, never()).save(any());
    }

    @Test
    void theAvailabilityToggleAndItsTerminalEdge() {
        Product product = product();
        ProductVariant variant = variant(product);
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVariant suspended = service.suspend(variant.getId(), authentication);
        assertThat(suspended.getStatus()).isEqualTo(ProductStatus.SUSPENDED);

        ProductVariant reactivated = service.reactivate(variant.getId(), authentication);
        assertThat(reactivated.getStatus()).isEqualTo(ProductStatus.ACTIVE);

        ProductVariant archived = service.archive(variant.getId(), authentication);
        assertThat(archived.getStatus()).isEqualTo(ProductStatus.ARCHIVED);

        // ARCHIVED is terminal — no transition out, the entity's own guard.
        assertThatThrownBy(() -> service.reactivate(variant.getId(), authentication))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRosterReadIsTheOwnersOwn() {
        Product product = product();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(seller);
        when(variantRepository.findByProductIdOrderBySkuAsc(product.getId())).thenReturn(java.util.List.of());

        service.listOwnedByProduct(product.getId(), authentication);

        verify(variantRepository).findByProductIdOrderBySkuAsc(product.getId());
    }

    @Test
    void aStrangerGetsTheHonest404OnTheVariantRead() {
        ProductVariant variant = variant(product());
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(stranger);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.getOwnedVariant(variant.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
