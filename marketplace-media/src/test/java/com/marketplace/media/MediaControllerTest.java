package com.marketplace.media;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private MediaService mediaService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private MediaController controller;

    @Test
    void requestUpload_returnsCreated() {
        UUID listingId = UUID.randomUUID();
        var view = new MediaService.MediaUploadView(UUID.randomUUID(), "k", "https://u", java.time.Duration.ofMinutes(15));
        var request = new MediaController.RequestUploadRequest(listingId, "image/jpeg", 1024L);
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(mediaService.requestUpload(listingId, "image/jpeg", 1024L, auth)).thenReturn(view);

        ResponseEntity<MediaService.MediaUploadView> result =
                controller.requestUpload(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(view, result.getBody());
    }

    @Test
    void confirmUpload_returnsOk() {
        UUID id = UUID.randomUUID();
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        var view = new MediaService.MediaAssetView(id, UUID.randomUUID(), "image/jpeg", 1L,
                "UPLOADED", 1, "https://u", null);
        when(mediaService.confirmUpload(id, auth)).thenReturn(view);

        assertEquals(HttpStatus.OK, controller.confirmUpload(id, auth).getStatusCode());
    }

    @Test
    void listByListing_returnsOk() {
        UUID listingId = UUID.randomUUID();
        when(mediaService.listByListing(listingId)).thenReturn(List.of());

        ResponseEntity<List<MediaService.MediaAssetView>> result = controller.listByListing(listingId);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void delete_returnsNoContent() {
        UUID id = UUID.randomUUID();
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);

        ResponseEntity<Void> result = controller.delete(id, auth);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(mediaService).delete(id, auth);
    }
}
