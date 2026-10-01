package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        var request = new MediaController.RequestUploadRequest(listingId, null, "image/jpeg", 1024L);
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(mediaService.requestUpload(listingId, "image/jpeg", 1024L, auth)).thenReturn(view);

        ResponseEntity<MediaService.MediaUploadView> result =
                controller.requestUpload(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(view, result.getBody());
    }

    /**
     * L48: the single upload channel's post target — the request carries
     * postId alone and reaches the post flow (the author gate lives in the
     * service).
     */
    @Test
    void requestUpload_postTarget_reachesThePostFlow() {
        UUID postId = UUID.randomUUID();
        var view = new MediaService.MediaUploadView(UUID.randomUUID(), "posts/k/u.jpg", "https://u",
                java.time.Duration.ofMinutes(15));
        var request = new MediaController.RequestUploadRequest(null, postId, "image/png", 2048L);
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(mediaService.requestPostUpload(postId, "image/png", 2048L, auth)).thenReturn(view);

        ResponseEntity<MediaService.MediaUploadView> result =
                controller.requestUpload(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(view, result.getBody());
        verify(mediaService).requestPostUpload(postId, "image/png", 2048L, auth);
    }

    /**
     * L48: the exactly-one-target type gate — carrying BOTH targets answers
     * the house 400 BEFORE any service call (the parseCategory discipline:
     * the contract's own words, never an enum-binding 500).
     */
    @Test
    void requestUpload_bothTargets_answers400BeforeAnyServiceCall() {
        var request = new MediaController.RequestUploadRequest(UUID.randomUUID(), UUID.randomUUID(),
                "image/jpeg", 1024L);
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> controller.requestUpload(request, auth));

        assertEquals("Exactly one target is required — listingId or postId (got both)", ex.getMessage());
        verifyNoInteractions(mediaService);
    }

    /** L48: the mirror — carrying NEITHER target answers the same 400. */
    @Test
    void requestUpload_noTarget_answers400BeforeAnyServiceCall() {
        var request = new MediaController.RequestUploadRequest(null, null, "image/jpeg", 1024L);
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> controller.requestUpload(request, auth));

        assertEquals("Exactly one target is required — listingId or postId (got neither)", ex.getMessage());
        verifyNoInteractions(mediaService);
    }

    @Test
    void confirmUpload_returnsOk() {
        UUID id = UUID.randomUUID();
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        var view = new MediaService.MediaAssetView(id, UUID.randomUUID(), null, "image/jpeg", 1L,
                "UPLOADED", 1, "https://u", null, null);
        when(mediaService.confirmUpload(id, auth)).thenReturn(view);

        assertEquals(HttpStatus.OK, controller.confirmUpload(id, auth).getStatusCode());
    }

    @Test
    void listByListing_returnsOk_andCarriesTheOptionalIdentity() {
        // R5: the public read passes the caller's Authentication through —
        // the L34 optional-identity seam (anonymous stays anonymous; a
        // valid JWT resolves the owner-viewer path inside the service).
        UUID listingId = UUID.randomUUID();
        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(mediaService.listByListing(listingId, auth)).thenReturn(List.of());

        ResponseEntity<List<MediaService.MediaAssetView>> result = controller.listByListing(listingId, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(mediaService).listByListing(listingId, auth);
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
