package com.marketplace.catalog;

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
import static org.mockito.Mockito.*;

/** The typed variants' new admin surface: the attribute read maps through
 *  the owning service and answers OK with the typed values (the module's
 *  controller test pattern — CatalogAttributeControllerTest verbatim). */
@ExtendWith(MockitoExtension.class)
class ProductVariantControllerTest {
    @Mock ProductVariantService variantService;
    @Mock Authentication authentication;
    @InjectMocks ProductVariantController controller;

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
}
