package com.marketplace.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * L39 (realestate systems plan §5 — SEO and structured data): the
 * read-only projection a sitemap page enumerates — exactly the two facts
 * the sitemaps.org 0.90 {@code <url>} entry carries for a listing page:
 * its identifier (to build {@code <loc>}) and the {@code updated_at}
 * audit column (to build {@code <lastmod>}). Nothing else is fetched —
 * a 50,000-URL page (the standard's per-file cap) must never load full
 * entities.
 */
public record SitemapEntry(
        UUID id,
        Instant updatedAt
) {
}
