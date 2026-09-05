package com.marketplace.media;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class MediaController {

    private final MediaService mediaService;
    private final CurrentUserProvider currentUserProvider;

    public MediaController(MediaService mediaService, CurrentUserProvider currentUserProvider) {
        this.mediaService = mediaService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Issue a presigned upload URL for a new listing photo. The client PUTs the
     * bytes directly to storage with the returned URL (and the declared
     * Content-Type), then calls the complete endpoint.
     */
    @PostMapping("/media/uploads")
    public ResponseEntity<MediaService.MediaUploadView> requestUpload(
            @Valid @RequestBody RequestUploadRequest request, Authentication authentication) {
        MediaService.MediaUploadView view = mediaService.requestUpload(
                request.listingId(), request.contentType(), request.sizeBytes(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Confirm the upload happened (verified server-side via HeadObject) and
     * make the asset visible on the listing.
     */
    @PostMapping("/media/{id}/complete")
    public ResponseEntity<MediaService.MediaAssetView> confirmUpload(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(mediaService.confirmUpload(id, authentication));
    }

    /**
     * Presigned read URLs for every uploaded asset of the listing, in display
     * order. Authenticated read — same visibility as the listing endpoints.
     */
    @GetMapping("/media/listings/{listingId}")
    public ResponseEntity<List<MediaService.MediaAssetView>> listByListing(
            @PathVariable UUID listingId) {
        return ResponseEntity.ok(mediaService.listByListing(listingId));
    }

    @DeleteMapping("/media/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        mediaService.delete(id, authentication);
        return ResponseEntity.noContent().build();
    }

    public record RequestUploadRequest(
            @NotNull UUID listingId,
            @NotBlank String contentType,
            @NotNull @Min(1) @Max(104857600) Long sizeBytes
    ) {
    }
}
