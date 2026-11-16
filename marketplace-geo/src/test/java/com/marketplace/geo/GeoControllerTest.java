package com.marketplace.geo;

import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoControllerTest {

    @Mock
    private GeoService geoService;

    @InjectMocks
    private GeoController controller;

    @Test
    void tree_returnsTheCachedTree() {
        GeoNode tree = new GeoNode(UUID.randomUUID(), null, 0, "سوريا", "Syria", "syria", List.of());
        when(geoService.getTree()).thenReturn(tree);

        ResponseEntity<GeoNode> result = controller.tree();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(tree, result.getBody());
        verify(geoService).getTree();
    }

    @Test
    void children_returnsServiceAnswer() {
        UUID parentId = UUID.randomUUID();
        List<GeoNode> children = List.of(
                new GeoNode(UUID.randomUUID(), parentId, 3, "البلد", null, "qudsayya-old-town"));
        when(geoService.getChildren(parentId)).thenReturn(children);

        ResponseEntity<List<GeoNode>> result = controller.children(parentId);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(children, result.getBody());
    }

    @Test
    void suggest_delegatesTheGatedPrefix() {
        when(geoService.suggest("قد")).thenReturn(List.of());

        ResponseEntity<List<GeoNode>> result = controller.suggest("قد");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(geoService).suggest("قد");
    }
}
