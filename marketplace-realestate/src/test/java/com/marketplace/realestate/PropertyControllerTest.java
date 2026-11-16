package com.marketplace.realestate;

import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyControllerTest {

    @Mock
    private RealestateService realestateService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private PropertyController controller;

    @Test
    void upsert_returnsTheStoredView() {
        UUID listingId = UUID.randomUUID();
        PropertyView view = new PropertyView(listingId, PropertyPurpose.RENT,
                PropertyType.APARTMENT, 120, 3, 2, 2, 5, 2015, true,
                java.util.List.of("elevator"), null, null, null, null);
        PropertyDetailsRequest request = new PropertyDetailsRequest(PropertyPurpose.RENT,
                PropertyType.APARTMENT, 120, 3, 2, 2, 5, 2015, true,
                java.util.List.of("elevator"), null, null, null, null);
        when(realestateService.upsert(listingId, request, authentication)).thenReturn(view);

        ResponseEntity<PropertyView> result = controller.upsert(listingId, request, authentication);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(view, result.getBody());
        verify(realestateService).upsert(listingId, request, authentication);
    }

    @Test
    void getByListingId_returnsThePublicView() {
        UUID listingId = UUID.randomUUID();
        PropertyView view = new PropertyView(listingId, PropertyPurpose.SALE,
                PropertyType.VILLA, null, null, null, null, null, null, null,
                null, null, null, null, null);
        when(realestateService.getPublicByListingId(listingId)).thenReturn(view);

        ResponseEntity<PropertyView> result = controller.getByListingId(listingId);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(view, result.getBody());
    }
}
