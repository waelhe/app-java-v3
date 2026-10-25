package com.marketplace.catalog;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The typed variants' whole owner surface: every endpoint maps through the
 *  owning service and answers its documented status (the module's controller
 *  test pattern — CatalogAttributeControllerTest verbatim; the Instancio
 *  fixture shape — ProductVariantServiceTest verbatim). */
@ExtendWith(MockitoExtension.class)
class ProductVariantControllerTest {
    @Mock ProductVariantService variantService;
    @Mock Authentication authentication;
    @InjectMocks ProductVariantController controller;

    private ProductVariant variant() {
        return Instancio.of(ProductVariant.class)
                .set(field(ProductVariant::getStatus), ProductStatus.ACTIVE)
                .create();
    }

    @Test void attributeReadMapsThroughTheOwningService() {
        UUID variantId = UUID.randomUUID();
        CatalogAttributeValueInput value = new CatalogAttributeValueInput(
                UUID.randomUUID(), "red", null, null, null);
        when(variantService.listOwnedAttributeValues(variantId, authentication))
                .thenReturn(List.of(value));
        var response = controller.listAttributes(variantId, authentication);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        verify(variantService).listOwnedAttributeValues(variantId, authentication);
    }

    @Test void registrationMapsTheCommandAndAnswersCreated() {
        UUID productId = UUID.randomUUID();
        ProductVariant created = variant();
        when(variantService.register(eq(productId), any(), eq(authentication))).thenReturn(created);
        var response = controller.register(productId, new ProductVariantController.RegisterVariantRequest(
                "ESPRESSO-2CUP-RED", "Large / Red", 149900L), authentication);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(created.getId());
        assertThat(response.getBody().status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test void rosterReadMapsTheProductsLiveVariants() {
        UUID productId = UUID.randomUUID();
        when(variantService.listOwnedByProduct(productId, authentication)).thenReturn(List.of(variant()));
        assertThat(controller.listByProduct(productId, authentication).getBody()).hasSize(1);
    }

    @Test void ownedReadMapsTheVariant() {
        ProductVariant owned = variant();
        when(variantService.getOwnedVariant(owned.getId(), authentication)).thenReturn(owned);
        assertThat(controller.getOwned(owned.getId(), authentication).getBody().id())
                .isEqualTo(owned.getId());
    }

    @Test void inventoryWriteMapsTheAbsoluteCount() {
        ProductVariant stocked = variant();
        when(variantService.setStock(stocked.getId(), 12, authentication)).thenReturn(stocked);
        assertThat(controller.setStock(stocked.getId(),
                new ProductVariantController.SetVariantStockRequest(12), authentication).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(variantService).setStock(stocked.getId(), 12, authentication);
    }

    @Test void lifecyclePostsMapThroughTheService() {
        ProductVariant v = variant();
        when(variantService.suspend(v.getId(), authentication)).thenReturn(v);
        when(variantService.reactivate(v.getId(), authentication)).thenReturn(v);
        when(variantService.archive(v.getId(), authentication)).thenReturn(v);
        assertThat(controller.suspend(v.getId(), authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.reactivate(v.getId(), authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.archive(v.getId(), authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(variantService).suspend(v.getId(), authentication);
        verify(variantService).reactivate(v.getId(), authentication);
        verify(variantService).archive(v.getId(), authentication);
    }
}
