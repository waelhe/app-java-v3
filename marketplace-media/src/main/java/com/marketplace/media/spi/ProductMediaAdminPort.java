package com.marketplace.media.spi;

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
 *
 * <p><b>Placement (the CI-measured ArchitectureRulesTest verdict):</b> this
 * seam carries Spring Security's {@code Authentication} — the authenticated
 * caller crosses it exactly like {@code com.marketplace.catalog.spi.
 * ProductAdminSpi} crosses the catalog seam — so it lives in the OWNING
 * module's {@code spi} package (the module-owned SPI pattern), never in
 * {@code shared.api}: the shared ports zone is framework-neutral by the
 * #429 build gate, and a port that imports a framework type there fails the
 * build. The module descriptor's dependency set is untouched — the media
 * module already allowed shared-security for exactly this shape.
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
