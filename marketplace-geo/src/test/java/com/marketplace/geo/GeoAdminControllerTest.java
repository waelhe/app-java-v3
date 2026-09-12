package com.marketplace.geo;

import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoAdminControllerTest {

    @Mock
    private GeoService geoService;

    @InjectMocks
    private GeoAdminController controller;

    @Test
    void create_returns201WithTheCreatedNode() {
        UUID parentId = UUID.randomUUID();
        GeoNode created = new GeoNode(UUID.randomUUID(), parentId, 2, "قدسيا", null, "qudsayya");
        when(geoService.createChild(parentId, "قدسيا", null, "qudsayya")).thenReturn(created);

        ResponseEntity<GeoNode> result = controller.create(
                new GeoAdminController.CreateGeoLocationRequest(parentId, "قدسيا", null, "qudsayya"));

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(created, result.getBody());
    }

    @Test
    void update_returnsTheAmendedNode() {
        UUID id = UUID.randomUUID();
        GeoNode updated = new GeoNode(id, UUID.randomUUID(), 2, "قدسيا الكبرى", null, "qudsayya-city");
        when(geoService.update(id, "قدسيا الكبرى", null, "qudsayya-city")).thenReturn(updated);

        ResponseEntity<GeoNode> result = controller.update(id,
                new GeoAdminController.UpdateGeoLocationRequest("قدسيا الكبرى", null, "qudsayya-city"));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(updated, result.getBody());
    }

    @Test
    void delete_returns204AndDelegates() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> result = controller.delete(id);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(geoService).delete(id);
    }
}
