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

@ExtendWith(MockitoExtension.class)
class ProductTypeAttributeControllerTest {
    @Mock ProductTypeAttributeService service;
    @InjectMocks ProductTypeAttributeController controller;

    @Test void listAssignAndRemoveRoutesMapTheAssignmentContract() {
        UUID productTypeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        ProductTypeAttributeAssignment assignment = ProductTypeAttributeAssignment.create(productTypeId, attributeId,
                ProductTypeAttributeScope.PRODUCT, true, 0);
        when(service.list(productTypeId)).thenReturn(List.of(assignment));
        assertThat(controller.list(productTypeId).getBody()).hasSize(1);
        when(service.assign(productTypeId, attributeId, ProductTypeAttributeScope.PRODUCT, true, 0))
                .thenReturn(assignment);
        var created = controller.assign(productTypeId, new ProductTypeAttributeController.AssignAttributeRequest(
                attributeId, ProductTypeAttributeScope.PRODUCT, true, 0));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().scope()).isEqualTo(ProductTypeAttributeScope.PRODUCT);
        assertThat(controller.remove(assignment.getId()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).remove(assignment.getId());
    }

    @Test void setRequiredRouteMapsThePromotionContract() {
        // The 2026-11-07 #808 round-10 review: the required-flag promotion
        // route — the migration path's executable half.
        UUID productTypeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        ProductTypeAttributeAssignment promoted = ProductTypeAttributeAssignment.create(productTypeId, attributeId,
                ProductTypeAttributeScope.PRODUCT, true, 0);
        when(service.setRequired(promoted.getId(), true)).thenReturn(promoted);

        var response = controller.setRequired(promoted.getId(),
                new ProductTypeAttributeController.SetRequiredRequest(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().required()).isTrue();
        verify(service).setRequired(promoted.getId(), true);
    }
}
