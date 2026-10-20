package com.marketplace.shared.api;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provider-scoped product-media administration contract used by transport
 * adapters. The media module remains the sole owner of storage keys, signed
 * URLs, ownership checks, confirmation, and deletion semantics.
 */
@NamedInterface("product-media-admin")
public interface ProductMediaAdminPort {

    UploadView requestUpload(UUID productId, String contentType, long sizeBytes,
                             Authentication authentication);

    MediaAssetView confirmUpload(UUID mediaId, Authentication authentication);

    List<MediaAssetView> listByProduct(UUID productId, Authentication authentication);

    void delete(UUID mediaId, Authentication authentication);

    record UploadView(UUID mediaId, String objectKey, String uploadUrl, Duration urlLifetime) { }

    record MediaAssetView(
            UUID id,
            UUID productId,
            String contentType,
            long sizeBytes,
            String status,
            int position,
            String downloadUrl,
            String thumbUrl,
            Instant createdAt) { }
}
