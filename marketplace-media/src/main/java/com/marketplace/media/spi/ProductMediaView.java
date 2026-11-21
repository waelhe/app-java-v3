package com.marketplace.media.spi;

import java.util.UUID;

/**
 * N-slice: the product-media row's own bounded projection — the durable
 * facts the module owns that the Saleor {@code ProductMedia} type's
 * bounded closure carries: the row's identity, the owning product and
 * the display sort order. The pinned type's unowned members (alt, url,
 * oembedData, the media type, the per-locale translation) stay
 * undeclared at the SDL boundary — the recorded bounded-closure
 * omissions, never a fabricated field.
 *
 * @param id        the row's native UUID (the typed global ID is the
 *                  transport boundary's own concern)
 * @param productId the owning product's native UUID
 * @param position  the 1-based display sort order
 */
public record ProductMediaView(UUID id, UUID productId, Integer position) {
}
