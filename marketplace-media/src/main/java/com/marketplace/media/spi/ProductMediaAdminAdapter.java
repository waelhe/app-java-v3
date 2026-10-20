package com.marketplace.media.spi;

import com.marketplace.media.MediaService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Transport-neutral product-media administration adapter. All ownership,
 * upload validation, storage signing, and soft-delete rules remain in
 * MediaService; this adapter only maps the shared boundary projection.
 */
@Component
public class ProductMediaAdminAdapter implements ProductMediaAdminPort {

    private final MediaService mediaService;

    public ProductMediaAdminAdapter(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Override
    public UploadView requestUpload(UUID productId, String contentType, long sizeBytes,
                                    Authentication authentication) {
        MediaService.MediaUploadView view =
                mediaService.requestProductUpload(productId, contentType, sizeBytes, authentication);
        return new UploadView(view.mediaId(), view.objectKey(), view.uploadUrl(), view.urlLifetime());
    }

    @Override
    public MediaAssetView confirmUpload(UUID mediaId, Authentication authentication) {
        return toView(mediaService.confirmUpload(mediaId, authentication));
    }

    @Override
    public List<MediaAssetView> listByProduct(UUID productId, Authentication authentication) {
        return mediaService.listByProduct(productId, authentication).stream()
                .map(ProductMediaAdminAdapter::toView)
                .toList();
    }

    @Override
    public void delete(UUID mediaId, Authentication authentication) {
        mediaService.delete(mediaId, authentication);
    }

    private static MediaAssetView toView(MediaService.MediaAssetView view) {
        if (view.productId() == null) {
            throw new IllegalStateException("Product media contract returned an asset without a product target");
        }
        return new MediaAssetView(view.id(), view.productId(), view.contentType(), view.sizeBytes(),
                view.status(), view.position(), view.downloadUrl(), view.thumbUrl(), view.createdAt());
    }
}
