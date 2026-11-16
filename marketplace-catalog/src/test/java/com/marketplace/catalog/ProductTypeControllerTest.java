package com.marketplace.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The configuration dictionary's own routes: every endpoint maps through
 *  the service and answers its documented status (the module's controller
 *  test pattern — CatalogAttributeControllerTest verbatim). */
@ExtendWith(MockitoExtension.class)
class ProductTypeControllerTest {
    @Mock ProductTypeService service;
    @InjectMocks ProductTypeController controller;

    @Test void configurationListMapsTheDictionary() {
        ProductType type = ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null);
        when(service.listForConfiguration()).thenReturn(List.of(type));
        assertThat(controller.listForConfiguration().getBody()).hasSize(1);
        assertThat(controller.listForConfiguration().getBody().get(0).code()).isEqualTo("home-appliances");
    }

    @Test void adminReadMapsTheType() {
        ProductType type = ProductType.create("home-appliances", "Home appliances", null, null);
        when(service.get(type.getId())).thenReturn(type);
        assertThat(controller.get(type.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.get(type.getId()).getBody().code()).isEqualTo("home-appliances");
    }

    @Test void registrationMapsThroughAndAnswersCreated() {
        ProductType created = ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", "Reusable.");
        when(service.register("home-appliances", "Home appliances", "الأجهزة المنزلية", "Reusable.")).thenReturn(created);
        var response = controller.register(new ProductTypeController.RegisterProductTypeRequest(
                "home-appliances", "Home appliances", "الأجهزة المنزلية", "Reusable."));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().code()).isEqualTo("home-appliances");
    }

    @Test void updateMapsTheImmutableCodeEdge() {
        ProductType updated = ProductType.create("home-appliances", "Small appliances", null, null);
        when(service.update(updated.getId(), "Small appliances", null, null)).thenReturn(updated);
        assertThat(controller.update(updated.getId(), new ProductTypeController.UpdateProductTypeRequest(
                "Small appliances", null, null)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void removalMapsThroughAndAnswersNoContent() {
        UUID id = UUID.randomUUID();
        assertThat(controller.remove(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).remove(id);
    }
}
