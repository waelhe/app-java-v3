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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogAttributeControllerTest {
    @Mock CatalogAttributeService service;
    @InjectMocks CatalogAttributeController controller;

    @Test void definitionRoutesMapRegistrationListAndUpdate() {
        CatalogAttributeDefinition definition = CatalogAttributeDefinition.create("color", "Color", "اللون", CatalogAttributeType.CHOICE, null);
        when(service.listDefinitions()).thenReturn(List.of(definition));
        assertThat(controller.listDefinitions().getBody()).hasSize(1);
        when(service.registerDefinition("color", "Color", "اللون", CatalogAttributeType.CHOICE, null)).thenReturn(definition);
        var created = controller.register(new CatalogAttributeController.RegisterDefinitionRequest(
                "color", "Color", "اللون", CatalogAttributeType.CHOICE, null));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().valueType()).isEqualTo(CatalogAttributeType.CHOICE);
        when(service.updateDefinition(definition.getId(), "Colour", "اللون", null)).thenReturn(definition);
        assertThat(controller.update(definition.getId(), new CatalogAttributeController.UpdateDefinitionRequest(
                "Colour", "اللون", null)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void optionRoutesMapListCreateAndRemoval() {
        UUID definitionId = UUID.randomUUID();
        CatalogAttributeOption option = CatalogAttributeOption.create(definitionId, "red", "Red", "أحمر", 0);
        when(service.listOptions(definitionId)).thenReturn(List.of(option));
        assertThat(controller.listOptions(definitionId).getBody()).hasSize(1);
        when(service.registerOption(definitionId, "red", "Red", "أحمر", 0)).thenReturn(option);
        var created = controller.registerOption(definitionId,
                new CatalogAttributeController.RegisterOptionRequest("red", "Red", "أحمر", 0));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().code()).isEqualTo("red");
        assertThat(controller.removeOption(option.getId()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).removeOption(option.getId());
    }
}
