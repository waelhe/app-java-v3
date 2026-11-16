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

/** The store-categories dictionary's own routes: every endpoint maps through
 *  the service and answers its documented status (the module's controller
 *  test pattern — ProductTypeControllerTest verbatim). */
@ExtendWith(MockitoExtension.class)
class StoreCategoryControllerTest {
    @Mock StoreCategoryService service;
    @InjectMocks StoreCategoryController controller;

    @Test void configurationListMapsTheDictionary() {
        StoreCategory category = StoreCategory.register("home-essentials", "Home essentials", "أساسيات المنزل", 10);
        when(service.listForConfiguration()).thenReturn(List.of(category));
        assertThat(controller.listForConfiguration().getBody()).hasSize(1);
        assertThat(controller.listForConfiguration().getBody().get(0).code()).isEqualTo("home-essentials");
        assertThat(controller.listForConfiguration().getBody().get(0).position()).isEqualTo(10);
    }

    @Test void adminReadMapsTheCategory() {
        StoreCategory category = StoreCategory.register("home-essentials", "Home essentials", null, 0);
        when(service.get(category.getId())).thenReturn(category);
        assertThat(controller.get(category.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.get(category.getId()).getBody().code()).isEqualTo("home-essentials");
    }

    @Test void registrationMapsThroughAndAnswersCreated() {
        StoreCategory created = StoreCategory.register("home-essentials", "Home essentials", "أساسيات المنزل", 10);
        when(service.register("home-essentials", "Home essentials", "أساسيات المنزل", 10)).thenReturn(created);
        var response = controller.register(new StoreCategoryController.RegisterStoreCategoryRequest(
                "home-essentials", "Home essentials", "أساسيات المنزل", 10));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().code()).isEqualTo("home-essentials");
        assertThat(response.getBody().position()).isEqualTo(10);
    }

    @Test void updateMapsTheImmutableCodeEdge() {
        StoreCategory updated = StoreCategory.register("home-essentials", "Daily essentials", null, 20);
        when(service.update(updated.getId(), "Daily essentials", null, 20)).thenReturn(updated);
        assertThat(controller.update(updated.getId(), new StoreCategoryController.UpdateStoreCategoryRequest(
                "Daily essentials", null, 20)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void removalMapsThroughAndAnswersNoContent() {
        UUID id = UUID.randomUUID();
        assertThat(controller.remove(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).remove(id);
    }
}
