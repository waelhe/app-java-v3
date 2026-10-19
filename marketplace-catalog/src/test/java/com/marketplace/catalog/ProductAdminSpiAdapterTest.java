package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductAdminSpi;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The administrative projection seam's own unit gate: every SPI method maps
 * through the same secured domain services and projects the exact wire
 * shapes — no domain or authorization rules live here (the module's
 * controller test pattern verbatim; the Instancio fixture shape —
 * ProductVariantServiceTest verbatim).
 */
@ExtendWith(MockitoExtension.class)
class ProductAdminSpiAdapterTest {
    @Mock ProductService products;
    @Mock ProductTypeService productTypes;
    @Mock ProductTypeAttributeService typeAttributes;
    @Mock CatalogAttributeService attributes;
    @Mock ProductVariantService variants;
    @Mock Authentication authentication;
    @InjectMocks ProductAdminSpiAdapter adapter;

    @Test
    void ownedProductsPageMapsThroughTheProjection() {
        Product product = Product.register("home-appliances", "Espresso", "Pump", 149900L, "SAR", UUID.randomUUID());
        PageRequest pageable = PageRequest.of(0, 10);
        when(products.listOwnedProducts(authentication, pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 1));

        var page = adapter.listOwnedProducts(authentication, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).title()).isEqualTo("Espresso");
        assertThat(page.getContent().get(0).priceMinor()).isEqualTo(149900L);
        assertThat(page.getContent().get(0).currency()).isEqualTo("SAR");
    }

    @Test
    void ownedProductReadProjectsTheDomainShape() {
        Product product = Product.register("home-appliances", "Espresso", null, 149900L, "SAR", UUID.randomUUID());
        when(products.getOwnedProduct(product.getId(), authentication)).thenReturn(product);

        var view = adapter.getOwnedProduct(product.getId(), authentication);

        assertThat(view.id()).isEqualTo(product.getId());
        assertThat(view.status()).isEqualTo(product.getStatus().name());
        assertThat(view.providerId()).isEqualTo(product.getProviderId());
    }

    @Test
    void productTypeProjectionCarriesAssignmentsAndChoiceOptions() {
        CatalogAttributeDefinition color = CatalogAttributeDefinition.create(
                "color", "Color", "اللون", CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption red = CatalogAttributeOption.create(
                color.getId(), "red", "Red", "أحمر", 0);
        ProductType type = ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null);
        ProductTypeAttributeAssignment selection = ProductTypeAttributeAssignment.create(
                type.getId(), color.getId(), ProductTypeAttributeScope.VARIANT_SELECTION, true, 0);
        when(attributes.listDefinitions()).thenReturn(List.of(color));
        when(productTypes.listForConfiguration()).thenReturn(List.of(type));
        when(typeAttributes.list(type.getId())).thenReturn(List.of(selection));
        when(attributes.listOptions(color.getId())).thenReturn(List.of(red));

        List<ProductAdminSpi.ProductTypeView> views = adapter.listProductTypes();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).code()).isEqualTo("home-appliances");
        var projected = views.get(0).attributes().get(0);
        assertThat(projected.code()).isEqualTo("color");
        assertThat(projected.valueType()).isEqualTo("CHOICE");
        assertThat(projected.scope()).isEqualTo("VARIANT_SELECTION");
        assertThat(projected.required()).isTrue();
        assertThat(projected.options()).hasSize(1);
        assertThat(projected.options().get(0).code()).isEqualTo("red");
        assertThat(projected.options().get(0).nameAr()).isEqualTo("أحمر");
    }

    @Test
    void aMissingDefinitionInTheTypeProjectionFailsLoudly() {
        ProductType type = ProductType.create("home-appliances", "Home appliances", null, null);
        ProductTypeAttributeAssignment orphan = ProductTypeAttributeAssignment.create(
                type.getId(), UUID.randomUUID(), ProductTypeAttributeScope.PRODUCT, false, 0);
        when(attributes.listDefinitions()).thenReturn(List.of());
        when(productTypes.listForConfiguration()).thenReturn(List.of(type));
        when(typeAttributes.list(type.getId())).thenReturn(List.of(orphan));

        assertThatThrownBy(() -> adapter.listProductTypes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing attribute definition");
    }

    @Test
    void productCommandsMapThroughTheDomainCommandShapes() {
        Product saved = Product.register("home-appliances", "Espresso", "Pump", 149900L, "SAR", UUID.randomUUID());
        when(products.register(any(), eq(authentication))).thenReturn(saved);

        var created = adapter.createProduct(new ProductAdminSpi.CreateProductCommand(
                "home-appliances", "Espresso", "Pump", 149900L, "SAR", null), authentication);

        assertThat(created.id()).isEqualTo(saved.getId());
        verify(products).register(new ProductService.RegisterProductCommand(
                "home-appliances", "Espresso", "Pump", 149900L, "SAR", null, List.of()), authentication);

        when(products.updateOwnedProduct(eq(saved.getId()), any(), eq(authentication))).thenReturn(saved);
        var updated = adapter.updateProduct(saved.getId(), new ProductAdminSpi.UpdateProductCommand(
                "New title", null, 159900L), authentication);

        assertThat(updated.id()).isEqualTo(saved.getId());
        verify(products).updateOwnedProduct(eq(saved.getId()), eq(new ProductService.UpdateProductCommand(
                "New title", null, 159900L)), eq(authentication));

        when(products.publishOwnedProduct(saved.getId(), authentication)).thenReturn(saved);
        assertThat(adapter.publishProduct(saved.getId(), authentication).id()).isEqualTo(saved.getId());
    }

    @Test
    void attributeValuesMapBothDirectionsPreservingTheWireDecimals() {
        UUID productId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        when(products.replaceOwnedAttributeValues(eq(productId), any(), eq(authentication)))
                .thenReturn(List.of(new CatalogAttributeValueInput(
                        assignmentId, null, new java.math.BigDecimal("1.50"), null, null)));

        var replaced = adapter.replaceProductAttributeValues(productId, List.of(
                new ProductAdminSpi.AttributeValueInput(assignmentId, null, "1.50", null, null)), authentication);

        assertThat(replaced.get(0).numberValue()).isEqualTo("1.5");
        verify(products).replaceOwnedAttributeValues(eq(productId), eq(List.of(
                new CatalogAttributeValueInput(assignmentId, null, new java.math.BigDecimal("1.50"), null, null))),
                eq(authentication));

        when(products.listOwnedAttributeValues(productId, authentication)).thenReturn(List.of(
                new CatalogAttributeValueInput(assignmentId, "Cotton", null, null, null)));
        assertThat(adapter.listProductAttributeValues(productId, authentication).get(0).textValue())
                .isEqualTo("Cotton");
    }

    @Test
    void variantSurfaceMapsCommandsAndProjections() {
        ProductVariant variant = Instancio.of(ProductVariant.class)
                .set(field(ProductVariant::getStatus), ProductStatus.ACTIVE)
                .create();
        UUID productId = UUID.randomUUID();
        when(variants.register(eq(productId), any(), eq(authentication))).thenReturn(variant);

        var created = adapter.createProductVariant(productId, new ProductAdminSpi.CreateProductVariantCommand(
                "SKU-1", "Red", 149900L, null), authentication);

        assertThat(created.id()).isEqualTo(variant.getId());
        assertThat(created.status()).isEqualTo("ACTIVE");
        verify(variants).register(eq(productId), eq(new ProductVariantService.RegisterVariantCommand(
                "SKU-1", "Red", 149900L, List.of())), eq(authentication));

        when(variants.listOwnedByProduct(productId, authentication)).thenReturn(List.of(variant));
        assertThat(adapter.listProductVariants(productId, authentication)).hasSize(1);

        when(variants.listOwnedAttributeValues(variant.getId(), authentication)).thenReturn(List.of(
                new CatalogAttributeValueInput(UUID.randomUUID(), null, null, true, null)));
        assertThat(adapter.listVariantAttributeValues(variant.getId(), authentication).get(0).booleanValue())
                .isTrue();
    }
}
